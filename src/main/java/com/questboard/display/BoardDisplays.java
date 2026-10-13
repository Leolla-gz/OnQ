package com.questboard.display;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.questboard.QuestManager;
import com.questboard.model.BlockLocation;
import com.questboard.model.Quest;
import com.questboard.model.Requirement;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 壁に設置したクエストボードの「見た目」を担当する。
 * <p>
 * Minecraft 標準（バニラ）の表示用エンティティを、ボードの前に浮かべて表示する。
 * <ul>
 *   <li>text_display … 文字を表示する（タイトル、依頼の概要、依頼主の顔）</li>
 *   <li>interaction  … 見えない当たり判定。左右クリックを受け取る</li>
 * </ul>
 * どれもバニラのエンティティなので、参加者は mod なしで見える。
 * 作成・削除は /summon・/kill コマンドをサーバ内部で実行して行う。
 * <pre>
 * レイアウト（3×3 の壁の中央ブロックを登録した場合）:
 *   ┌───────────────┐
 *   │ [◀ 前] クエストボード [次 ▶]  │ ← タイトルと、ページ送り（7 件以上あるときだけ）
 *   │ [顔]     [顔]     [顔]        │
 *   │ #1 Steve #2 Alex  #3 ...      │ ← 依頼 1〜3
 *   │ 石炭 3/3 原木 5/10            │
 *   │ [顔]     [顔]     [顔]        │ ← 依頼 4〜6
 *   └───────────────┘
 * </pre>
 */
public final class BoardDisplays {
	/** 表示更新の間隔（40 tick = 約 2 秒） */
	private static final int REFRESH_TICKS = 40;
	/** 1 枚のボードに並べる依頼の数（3 列 × 2 段） */
	public static final int CELLS = 6;
	private static final int COLUMNS = 3;
	/** タイトル用のマス番号 */
	private static final int TITLE_SLOT = CELLS;
	/** ページ送りのボタン用のマス番号 */
	private static final int PREV_SLOT = CELLS + 1;
	private static final int NEXT_SLOT = CELLS + 2;
	/** 1 枚のボードで覚えておく表示内容の数（依頼 + タイトル + ボタン 2 個） */
	private static final int SLOTS = CELLS + 3;
	/** ページを送ったまま誰も操作しなければ、1 ページ目に戻すまでの時間（600 tick = 約 30 秒） */
	private static final int PAGE_RESET_TICKS = 600;
	/** 1 マスに表示する納品物の最大行数 */
	private static final int MAX_LINES_PER_CELL = 3;
	/** 文字の背景色（ARGB: 半透明の焦げ茶 = 掲示板の板っぽい色） */
	private static final int BACKGROUND = 0xC02B1A0E;
	/** 顔の拡大率。顔は文字 1 個分（8 ドット = 0.2 ブロック）なので、約 0.44 ブロック角になる */
	private static final float FACE_SCALE = 2.2f;
	/** 1 マスに表示する納品先メモの最大文字数（長いと折り返して顔に重なるため） */
	private static final int MAX_MEMO_CHARS_PER_CELL = 10;

	/** このmodが出したエンティティすべてに付ける目印 */
	private static final String TAG_ALL = "questboard";

	private final MinecraftServer server;
	/**
	 * 今回のサーバ起動で付ける目印。前回起動時の古い表示はこれを持たないので、読み込まれたら消す
	 * （サーバ停止中に依頼が変わっても、古い表示が残らないようにするため）
	 */
	private final String sessionTag = "qbs_" + Integer.toHexString(ThreadLocalRandom.current().nextInt());

	/** ボードごとの「表示済みの内容」。変化したマスだけ作り直すために覚えておく */
	private final Map<String, String[]> rendered = new HashMap<>();
	/** ボードごとの「各マスに表示中のクエスト番号」（クリック判定用。0 = 空き） */
	private final Map<String, int[]> slotQuests = new HashMap<>();
	/** ボードのチャンクが連続で読み込まれている回数（読み込み直後の誤動作防止） */
	private final Map<String, Integer> loadedStreak = new HashMap<>();
	/** ボードごとの「表示中のページ」（0 始まり）。ボードは全員が同じ物を見るので、ページも全員で共通 */
	private final Map<String, Integer> pages = new HashMap<>();
	/** ボードごとの「最後にページを送った時刻」（サーバの tick 数） */
	private final Map<String, Integer> lastPageTurn = new HashMap<>();
	private int tickCounter;

	public BoardDisplays(MinecraftServer server) {
		this.server = server;
	}

	// ------------------------------------------------------------------
	// 定期更新
	// ------------------------------------------------------------------

	/** 毎 tick 呼ばれる。REFRESH_TICKS ごとに全ボードの表示を確認・更新する */
	public void onServerTick(QuestManager manager) {
		if (++tickCounter < REFRESH_TICKS) {
			return;
		}
		tickCounter = 0;
		Set<String> activeKeys = new HashSet<>();
		for (BlockLocation board : manager.boards()) {
			activeKeys.add(key(board));
			render(manager, board);
		}
		// 解除されたボードの記憶を捨てる
		rendered.keySet().retainAll(activeKeys);
		slotQuests.keySet().retainAll(activeKeys);
		loadedStreak.keySet().retainAll(activeKeys);
		pages.keySet().retainAll(activeKeys);
		lastPageTurn.keySet().retainAll(activeKeys);
	}

	private void render(QuestManager manager, BlockLocation board) {
		String key = key(board);
		ServerLevel level = manager.findLevel(board.dimension);
		if (level == null) {
			return;
		}
		// チャンクが読み込まれていなければ何もしない（誰も見ていない）
		if (!level.hasChunkAt(board.toBlockPos())) {
			loadedStreak.put(key, 0);
			return;
		}
		// 読み込み直後はエンティティの読み込みが追いついていないことがあるので、1 回待つ
		if (loadedStreak.merge(key, 1, Integer::sum) < 2) {
			return;
		}

		String[] previous = rendered.get(key);
		if (previous == null) {
			// このボードを初めて描く: 前回起動時などの残骸をまとめて消す
			run(level, "kill @e[tag=" + boardTag(key) + "]");
			previous = new String[SLOTS];
			rendered.put(key, previous);
		}
		int[] questIds = slotQuests.computeIfAbsent(key, k -> new int[CELLS]);
		BoardLayout layout = new BoardLayout(board);
		List<Quest> quests = manager.quests();

		// 表示するページ（依頼が減ってページが無くなったら、最後のページにする）
		int pageCount = Math.max(1, (quests.size() + CELLS - 1) / CELLS);
		int page = Math.clamp(pages.getOrDefault(key, 0), 0, pageCount - 1);
		if (page > 0 && server.getTickCount() - lastPageTurn.getOrDefault(key, 0) > PAGE_RESET_TICKS) {
			page = 0; // しばらく誰も操作していないので、1 ページ目に戻す
		}
		pages.put(key, page);

		// タイトル
		String title = titleText(quests.size(), page, pageCount);
		if (!title.equals(previous[TITLE_SLOT])) {
			run(level, "kill @e[tag=" + cellTag(key, TITLE_SLOT) + "]");
			run(level, summonText(layout.title(), layout.yaw(), title, 0.4f, 400, tags(key, TITLE_SLOT)));
			previous[TITLE_SLOT] = title;
		}

		// ページ送りのボタン（2 ページ以上あるときだけ出す）
		renderPageButton(level, key, layout, previous, PREV_SLOT, pageCount > 1 ? "◀ 前" : "");
		renderPageButton(level, key, layout, previous, NEXT_SLOT, pageCount > 1 ? "次 ▶" : "");

		// 依頼 1 件ずつ（内容が変わったマスだけ作り直す）
		for (int slot = 0; slot < CELLS; slot++) {
			int index = page * CELLS + slot;
			Quest quest = index < quests.size() ? quests.get(index) : null;
			questIds[slot] = quest != null ? quest.id : 0;
			String text = quest != null ? cellText(quest, manager.lastCounts(quest)) : "";
			String desired = quest != null ? quest.ownerName + "|" + text : "";
			if (desired.equals(previous[slot])) {
				continue;
			}
			run(level, "kill @e[tag=" + cellTag(key, slot) + "]");
			if (quest != null) {
				int column = slot % COLUMNS;
				int row = slot / COLUMNS;
				String cellTags = tags(key, slot);
				run(level, summonFace(layout.face(column, row), layout.yaw(), quest.ownerName, cellTags));
				run(level, summonText(layout.text(column, row), layout.yaw(), text, 0.3f, 120, cellTags));
				run(level, summonInteraction(layout.clickArea(column, row), 0.9f, 1.0f, cellTags));
			}
			previous[slot] = desired;
		}
	}

	/** ページ送りのボタン 1 個を、必要なら作り直す（label が空なら消す） */
	private void renderPageButton(ServerLevel level, String key, BoardLayout layout, String[] previous, int slot, String label) {
		if (label.equals(previous[slot])) {
			return;
		}
		run(level, "kill @e[tag=" + cellTag(key, slot) + "]");
		if (!label.isEmpty()) {
			boolean next = slot == NEXT_SLOT;
			String buttonTags = tags(key, slot);
			run(level, summonText(layout.pageButton(next), layout.yaw(), "[" + part(label, "aqua", true) + "]", 0.4f, 200, buttonTags));
			run(level, summonInteraction(layout.pageButtonClickArea(next), 0.5f, 0.35f, buttonTags));
		}
		previous[slot] = label;
	}

	/**
	 * クリックされたエンティティがページ送りのボタンなら、ページを送ってすぐ描き直す。
	 * 最後のページの次は 1 ページ目に戻る（前も同じ）
	 * @return ページ送りのボタンだったら true
	 */
	public boolean handlePageClick(QuestManager manager, Entity entity) {
		Set<String> tags = entity.entityTags();
		String boardKey = tags.contains(TAG_ALL) ? findBoardKey(tags) : null;
		if (boardKey == null) {
			return false;
		}
		int delta = tags.contains(cellTag(boardKey, NEXT_SLOT)) ? 1 : tags.contains(cellTag(boardKey, PREV_SLOT)) ? -1 : 0;
		if (delta == 0) {
			return false;
		}
		int pageCount = Math.max(1, (manager.quests().size() + CELLS - 1) / CELLS);
		pages.put(boardKey, Math.floorMod(pages.getOrDefault(boardKey, 0) + delta, pageCount));
		lastPageTurn.put(boardKey, server.getTickCount());
		for (BlockLocation board : manager.boards()) {
			if (key(board).equals(boardKey)) {
				render(manager, board);
			}
		}
		return true;
	}

	// ------------------------------------------------------------------
	// 外部から呼ばれる処理
	// ------------------------------------------------------------------

	/** ボード解除時: 表示を消す */
	public void onBoardRemoved(QuestManager manager, BlockLocation board) {
		String key = key(board);
		ServerLevel level = manager.findLevel(board.dimension);
		if (level != null) {
			run(level, "kill @e[tag=" + boardTag(key) + "]");
		}
		rendered.remove(key);
		slotQuests.remove(key);
		loadedStreak.remove(key);
		pages.remove(key);
		lastPageTurn.remove(key);
	}

	/** /quest board refresh 用: 次の更新で全ボードを描き直す */
	public void forceRedraw() {
		rendered.clear();
	}

	/**
	 * エンティティが読み込まれたとき: このmodの古い表示（前回起動時のもの・解除済みボードのもの）なら消す
	 */
	public void onEntityLoad(Entity entity, Set<String> activeBoardKeys) {
		Set<String> tags = entity.entityTags();
		if (!tags.contains(TAG_ALL)) {
			return;
		}
		String boardKey = findBoardKey(tags);
		if (!tags.contains(sessionTag) || boardKey == null || !activeBoardKeys.contains(boardKey)) {
			entity.discard();
		}
	}

	/**
	 * クリックされたエンティティが、どのクエストのマスかを返す。
	 * @return クエスト番号。このmodのマスでなければ -1、空きマスなら 0
	 */
	public int questIdAt(Entity entity) {
		Set<String> tags = entity.entityTags();
		if (!tags.contains(TAG_ALL)) {
			return -1;
		}
		String boardKey = findBoardKey(tags);
		int[] ids = boardKey != null ? slotQuests.get(boardKey) : null;
		for (String tag : tags) {
			if (ids != null && tag.startsWith("qbc_")) {
				// 例: qbc_<ボードキー>_3 の末尾がマス番号
				String slotText = tag.substring(tag.lastIndexOf('_') + 1);
				try {
					int slot = Integer.parseInt(slotText);
					return slot >= 0 && slot < CELLS ? ids[slot] : 0;
				} catch (NumberFormatException e) {
					return 0; // タイトル等
				}
			}
		}
		return 0;
	}

	public static String key(BlockLocation board) {
		// タグに使える文字（英数字 _ - . +）だけにする。例: minecraft.overworld_10_64_-20
		return board.dimension.replace(':', '.') + "_" + board.x + "_" + board.y + "_" + board.z;
	}

	// ------------------------------------------------------------------
	// 表示内容（テキストコンポーネントを SNBT 形式の文字列で組み立てる）
	// ------------------------------------------------------------------

	private static String titleText(int questCount, int page, int pageCount) {
		StringBuilder sb = new StringBuilder("[");
		sb.append(part("クエストボード", "gold", true));
		String pages = pageCount > 1 ? "（" + (page + 1) + "/" + pageCount + " ページ）" : "";
		sb.append(',').append(part("\n掲示中 " + questCount + " 件" + pages, "white", false));
		sb.append(',').append(part("\n右クリック: 受注(ブックマーク) / 左クリック: 詳細", "gray", false));
		return sb.append(']').toString();
	}

	/** 1 件分の概要。例: 「#3 Steve / 石炭 3/3 / 原木 5/10」 */
	private static String cellText(Quest quest, int[] counts) {
		StringBuilder sb = new StringBuilder("[");
		sb.append(part("#" + quest.id + " ", "yellow", true));
		sb.append(',').append(part(quest.ownerName, "white", false));
		if (quest.title != null) {
			String title = quest.title.length() > MAX_MEMO_CHARS_PER_CELL
					? quest.title.substring(0, MAX_MEMO_CHARS_PER_CELL) + "…" : quest.title;
			sb.append(',').append(part("\n" + title, "gold", false));
		}
		// クエスト名の行があるときは、顔に重ならないよう納品物を 1 行減らす
		int shown = Math.min(quest.requirements.size(), quest.title != null ? MAX_LINES_PER_CELL - 1 : MAX_LINES_PER_CELL);
		for (int i = 0; i < shown; i++) {
			Requirement r = quest.requirements.get(i);
			boolean done = counts != null && counts[i] >= r.count;
			String have = counts != null ? String.valueOf(counts[i]) : "?";
			String color = done ? "green" : "white";
			sb.append(',').append(part("\n", color, false));
			if (r.kind == Requirement.Kind.ITEM && r.translationKey != null) {
				// translate を使うと、見る人のゲーム言語でアイテム名が出る
				sb.append(",{translate:").append(quote(r.translationKey)).append(",color:\"").append(color).append("\"}");
			} else {
				sb.append(',').append(part("#" + r.id, color, false));
			}
			sb.append(',').append(part(" " + have + "/" + r.count, color, false));
		}
		if (quest.requirements.size() > shown) {
			sb.append(',').append(part("\nほか " + (quest.requirements.size() - shown) + " 種", "gray", false));
		}
		if (quest.chest == null) {
			sb.append(',').append(part("\n納品先 未設定", "gray", false));
		} else if (quest.chestMemo != null) {
			String memo = quest.chestMemo.length() > MAX_MEMO_CHARS_PER_CELL
					? quest.chestMemo.substring(0, MAX_MEMO_CHARS_PER_CELL) + "…" : quest.chestMemo;
			sb.append(',').append(part("\n→ " + memo, "gray", false));
		}
		return sb.append(']').toString();
	}

	/** {text:"...",color:"..."} の 1 部品 */
	private static String part(String text, String color, boolean bold) {
		return "{text:" + quote(text) + ",color:\"" + color + "\"" + (bold ? ",bold:true" : "") + "}";
	}

	/** SNBT の文字列リテラルにする（" と \ と改行をエスケープ） */
	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	// ------------------------------------------------------------------
	// /summon コマンドの組み立て
	// ------------------------------------------------------------------

	/** 拡大縮小だけの変形（回転なし） */
	private static String transformation(float scale) {
		return "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
				+ "scale:[" + scale + "f," + scale + "f," + scale + "f]}";
	}

	/**
	 * 文字の表示。billboard:"fixed" で壁と平行に固定する（見る人が動いても向きは変わらない）
	 */
	private static String summonText(double[] pos, float yaw, String text, float scale, int lineWidth, String tags) {
		return "summon text_display " + coords(pos) + " {" + tags + ",text:" + text
				+ ",billboard:\"fixed\",Rotation:[" + yaw + "f,0f],alignment:\"center\",line_width:" + lineWidth
				+ ",background:" + BACKGROUND + ",shadow:true," + transformation(scale) + "}";
	}

	/**
	 * 依頼主の顔の表示。文字の一種である「プレイヤーの顔」オブジェクト（真正面から見た平らな顔）を、
	 * 大きく拡大して壁に貼る。名前を指定すると、見る側がスキンを取得して顔を描く。
	 * 立体の頭と違って厚みが無く、向きも壁に固定（billboard:"fixed"）なので、壁にめり込まない
	 */
	private static String summonFace(double[] pos, float yaw, String ownerName, String tags) {
		return "summon text_display " + coords(pos) + " {" + tags
				+ ",text:{object:\"player\",player:{name:" + quote(ownerName) + "},hat:true}"
				+ ",billboard:\"fixed\",Rotation:[" + yaw + "f,0f],alignment:\"center\",background:0,"
				+ transformation(FACE_SCALE) + "}";
	}

	/** 見えない当たり判定（幅 × 高さ ブロックの箱）。response:true でクリック時に腕を振る */
	private static String summonInteraction(double[] pos, float width, float height, String tags) {
		return "summon interaction " + coords(pos) + " {" + tags + ",width:" + width + "f,height:" + height + "f,response:true}";
	}

	private static String coords(double[] pos) {
		return String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", pos[0], pos[1], pos[2]);
	}

	// ------------------------------------------------------------------
	// タグ（エンティティに付ける目印）
	// ------------------------------------------------------------------

	private static String boardTag(String key) {
		return "qbk_" + key;
	}

	private static String cellTag(String key, int slot) {
		String name = switch (slot) {
			case TITLE_SLOT -> "t";
			case PREV_SLOT -> "p";
			case NEXT_SLOT -> "n";
			default -> String.valueOf(slot);
		};
		return "qbc_" + key + "_" + name;
	}

	/** Tags:[...] 部分。全体・今回の起動・ボード・マスの 4 つの目印を付ける */
	private String tags(String key, int slot) {
		return "Tags:[\"" + TAG_ALL + "\",\"" + sessionTag + "\",\"" + boardTag(key) + "\",\"" + cellTag(key, slot) + "\"]";
	}

	private static String findBoardKey(Set<String> tags) {
		for (String tag : tags) {
			if (tag.startsWith("qbk_")) {
				return tag.substring(4);
			}
		}
		return null;
	}

	/** コマンドをサーバ権限・出力なしで実行する（チャットやログに「召喚しました」等を出さない） */
	private void run(ServerLevel level, String command) {
		CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, command);
	}
}
