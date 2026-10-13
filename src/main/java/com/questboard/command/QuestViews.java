package com.questboard.command;

import java.util.List;
import java.util.function.Consumer;

import com.questboard.QuestManager;
import com.questboard.model.Quest;
import com.questboard.model.Requirement;
import com.questboard.util.Texts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * クエスト一覧・詳細・ヘルプのチャット表示を組み立てる。
 * コマンドとボード右クリックの両方から使うので、出力先は Consumer（関数）で受け取る。
 */
public final class QuestViews {
	private QuestViews() {
	}

	/** /quest list の 1 ページに出す件数（1 件 3 行。多いとチャット欄に収まらない） */
	private static final int QUESTS_PER_LIST_PAGE = 5;

	/**
	 * クエストボード（一覧）表示。件数が多いときはページに分ける
	 * @param page 表示するページ（1 始まり。範囲外なら近いページにする）
	 */
	public static void sendBoard(Consumer<Component> out, QuestManager manager, int page) {
		List<Quest> quests = manager.quests();
		int pageCount = Math.max(1, (quests.size() + QUESTS_PER_LIST_PAGE - 1) / QUESTS_PER_LIST_PAGE);
		int current = Math.clamp(page, 1, pageCount);
		String pages = pageCount > 1 ? "・" + current + "/" + pageCount + " ページ" : "";
		out.accept(Component.literal("===== クエストボード（" + quests.size() + " 件" + pages + "） =====").withStyle(ChatFormatting.GOLD));
		if (quests.isEmpty()) {
			out.accept(Component.literal("掲示中のクエストはありません。/quest pick で発注できます").withStyle(ChatFormatting.GRAY));
			return;
		}
		int start = (current - 1) * QUESTS_PER_LIST_PAGE;
		for (Quest quest : quests.subList(start, Math.min(quests.size(), start + QUESTS_PER_LIST_PAGE))) {
			// 1 行目: 番号・依頼主・ボタン
			MutableComponent head = Component.literal("#" + quest.id + " ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(quest.titleLabel()).withStyle(ChatFormatting.GOLD))
					.append(Component.literal(quest.ownerName + " の依頼 ").withStyle(ChatFormatting.WHITE))
					.append(Texts.button("詳細", "/quest info " + quest.id, "詳細を表示"))
					.append(Component.literal(" "))
					.append(Texts.button("受注(ブックマーク)", "/quest accept " + quest.id, "このクエストを受注(ブックマーク)する"));
			out.accept(head);
			// 2 行目: 納品状況
			int[] counts = manager.currentOrLastCounts(quest);
			out.accept(Component.literal("   ").append(QuestManager.progressText(quest, counts)));
			// 3 行目: 納品先
			out.accept(Component.literal("   納品先: " + chestText(quest)).withStyle(ChatFormatting.GRAY));
		}
		if (pageCount > 1) {
			MutableComponent footer = Component.literal("");
			if (current > 1) {
				footer.append(Texts.button("前のページ", "/quest list " + (current - 1), (current - 1) + " ページ目を表示")).append(Component.literal(" "));
			}
			if (current < pageCount) {
				footer.append(Texts.button("次のページ", "/quest list " + (current + 1), (current + 1) + " ページ目を表示")).append(Component.literal(" "));
			}
			footer.append(Component.literal("/quest list <ページ> ・ 全部を画面で見るなら /quest open").withStyle(ChatFormatting.GRAY));
			out.accept(footer);
		}
	}

	/** 1 件の詳細表示 */
	public static void sendInfo(Consumer<Component> out, QuestManager manager, Quest quest) {
		int[] counts = manager.currentOrLastCounts(quest);
		out.accept(Component.literal("===== クエスト #" + quest.id + " =====").withStyle(ChatFormatting.GOLD));
		if (quest.title != null) {
			out.accept(Component.literal("クエスト名: " + quest.title).withStyle(ChatFormatting.YELLOW));
		}
		out.accept(Component.literal("依頼主: " + quest.ownerName));
		out.accept(Component.literal("納品先: " + chestText(quest)));
		int daysLeft = QuestManager.daysLeft(quest);
		if (daysLeft >= 0) {
			out.accept(Component.literal("期限: あと " + daysLeft + " 日（発注から " + QuestManager.EXPIRE_DAYS + " 日で自動取り消し）"));
		}
		out.accept(Component.literal("納品物:"));
		for (int i = 0; i < quest.requirements.size(); i++) {
			Requirement r = quest.requirements.get(i);
			String have = counts != null ? String.valueOf(counts[i]) : "?";
			boolean done = counts != null && counts[i] >= r.count;
			out.accept(Component.literal(done ? "  ✔ " : "  ・ ")
					.append(r.displayName())
					.append(Component.literal(" " + have + "/" + r.count))
					.withStyle(done ? ChatFormatting.GREEN : ChatFormatting.WHITE));
		}
		String accepted = quest.acceptedBy.isEmpty() ? "なし" : String.join(", ", quest.acceptedBy);
		out.accept(Component.literal("受注者: " + accepted));
		if (counts == null && quest.chest != null) {
			out.accept(Component.literal("※ 納品チェストが読み込まれていないか、壊されています").withStyle(ChatFormatting.GRAY));
		}
	}

	/** /quest mine の表示: 自分が出した依頼の進捗と、受注中の依頼 */
	public static void sendMine(Consumer<Component> out, QuestManager manager, ServerPlayer player) {
		List<Quest> owned = manager.questsOwnedBy(player);
		out.accept(Component.literal("===== 自分の依頼（" + owned.size() + " 件） =====").withStyle(ChatFormatting.GOLD));
		if (owned.isEmpty()) {
			out.accept(Component.literal("出している依頼はありません").withStyle(ChatFormatting.GRAY));
		}
		for (Quest quest : owned) {
			int[] counts = manager.currentOrLastCounts(quest);
			out.accept(Component.literal("#" + quest.id + quest.titleLabel() + " ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(percentText(quest, counts) + " ").withStyle(ChatFormatting.GREEN))
					.append(Texts.button("詳細", "/quest info " + quest.id, "詳細を表示"))
					.append(Component.literal(" "))
					.append(Texts.button("編集", "/quest pick " + quest.id, "画面で納品物を追加・削除する"))
					.append(Component.literal(" "))
					.append(Texts.button("取消", "/quest cancel " + quest.id, "この依頼を取り消す")));
			out.accept(Component.literal("   ").append(QuestManager.progressText(quest, counts)));
			String accepted = quest.acceptedBy.isEmpty() ? "なし" : String.join(", ", quest.acceptedBy);
			out.accept(Component.literal("   受注者: " + accepted + " / 納品先: " + chestText(quest)).withStyle(ChatFormatting.GRAY));
		}
		List<Quest> accepted = manager.questsAcceptedBy(player);
		if (!accepted.isEmpty()) {
			out.accept(Component.literal("===== 受注(ブックマーク)中（" + accepted.size() + " 件） =====").withStyle(ChatFormatting.GOLD));
			for (Quest quest : accepted) {
				int[] counts = manager.currentOrLastCounts(quest);
				out.accept(Component.literal("#" + quest.id + quest.titleLabel() + " " + quest.ownerName + " の依頼  ").withStyle(ChatFormatting.YELLOW)
						.append(QuestManager.progressText(quest, counts)));
			}
		}
	}

	/** 全体の達成率 例: 「達成率 65%」。数えられなければ「達成率 ?」 */
	private static String percentText(Quest quest, int[] counts) {
		if (counts == null || quest.requirements.isEmpty()) {
			return "達成率 ?";
		}
		long need = 0;
		long have = 0;
		for (int i = 0; i < quest.requirements.size(); i++) {
			need += quest.requirements.get(i).count;
			have += Math.min(counts[i], quest.requirements.get(i).count);
		}
		return "達成率 " + (have * 100 / need) + "%";
	}

	/** /quest help の表示 */
	public static void sendHelp(Consumer<Component> out, boolean canManageBoards, String guideUrl) {
		out.accept(Component.literal("===== クエストボードの使い方 =====").withStyle(ChatFormatting.GOLD));
		java.net.URI guide = parseUrl(guideUrl);
		if (guide != null) {
			out.accept(Component.literal("図入りの説明ページ: ").withStyle(ChatFormatting.WHITE).append(Texts.link(guide)));
		}
		// 画面（チェスト画面）で操作する方法を先に。アイテムの名前（ID）を覚えていなくても使える
		section(out, "▼ 画面で操作する（おすすめ）");
		line(out, "/quest open", "クエスト一覧の画面を開く（依頼を見る・受注(ブックマーク)する・[依頼を出す]）");
		line(out, "/quest pick", "依頼を出す: 画面でアイテムと個数を選ぶ → [終了] → 納品チェストを右クリック");
		line(out, "/quest pick <番号>", "自分の依頼の納品物を画面で追加・削除（品物をクリックで削除）");
		line(out, "/quest mine", "自分の依頼の進捗と受注(ブックマーク)中の依頼（[編集] [取消] ボタン付き）");
		line(out, "/quest name <番号> [<名前>]", "クエスト名を付ける（何に使うのか。例: 拠点の壁の材料）。省略で消す（依頼主）");
		line(out, "/quest cancel <番号>", "依頼を取り消す（依頼主・OP）");

		section(out, "▼ 見る・受注(ブックマーク)する・納品先");
		line(out, "/quest list [<ページ>]", "掲示中のクエスト一覧（5 件ずつ。[詳細] [受注(ブックマーク)] ボタン付き）");
		line(out, "/quest info <番号>", "クエストの詳細と納品状況");
		line(out, "/quest accept <番号>", "受注(ブックマーク)する（1 つのクエストを何人でも受注(ブックマーク)できる）");
		line(out, "/quest chest <番号> [<メモ>]", "納品チェストを選び直す。メモ（例: 拠点前）も付けられる（依頼主）");
		line(out, "/quest memo <番号> [<メモ>]", "納品チェストの場所のメモを付ける（例: 拠点前）。省略で消す（依頼主）");
		line(out, "/quest boarditem", "手に持った本をクエストボード（アイテム）に変える");
		line(out, "/quest guide", "図入りの説明ページの URL を表示する");

		section(out, "▼ コマンドで納品物を指定する（アイテムの ID が分かる人向け）");
		line(out, "/quest create [<アイテム> <個数> ...]", "発注（一度に最大4種）→ 納品チェストを右クリック");
		line(out, "/quest create hand [<個数>]", "手に持っているアイテムで発注");
		line(out, "/quest add <番号> <アイテム> <個数>", "納品物を追加（hand も可。依頼主）");
		line(out, "/quest addtag <番号> <タグ> <個数>", "例: minecraft:planks = 板材なら何でも（依頼主）");
		line(out, "/quest remove <番号> [<アイテム>]", "納品物を取り消す。省略すると [削除] ボタン付きの一覧（依頼主）");

		if (canManageBoards) {
			line(out, "/quest help op", "管理用のコマンド（ボードの設置など。OP）");
		}
	}

	/** /quest help op の表示: 管理用（OP）のコマンド */
	public static void sendOpHelp(Consumer<Component> out) {
		out.accept(Component.literal("===== クエストボードの管理（OP） =====").withStyle(ChatFormatting.GOLD));
		line(out, "/quest board add | remove", "右クリックしたブロックをボードに登録/解除");
		line(out, "/quest board list", "ボードの場所一覧");
		line(out, "/quest board refresh", "壁のボードの表示を作り直す");
		line(out, "/quest board demo <件数> | clear", "表示確認用の見本クエストを作る/消す");
		line(out, "/quest resetid", "クエスト番号を振り直す（掲示中が無ければ次は #1 から）");
		line(out, "/quest guide set <URL> | clear", "/quest guide で表示する説明ページの URL を設定/解除");
		line(out, "/quest cancel <番号>", "ほかの人の依頼も取り消せる");
		line(out, "/quest help", "ふつうの使い方");
	}

	/** /quest guide の表示: 説明ページの URL（クリックでブラウザが開く） */
	public static void sendGuide(Consumer<Component> out, String guideUrl, boolean canManageBoards) {
		java.net.URI guide = parseUrl(guideUrl);
		if (guide == null) {
			out.accept(Texts.info("説明ページの URL はまだ設定されていません。コマンドの一覧は /quest help で見られます"));
			if (canManageBoards) {
				out.accept(Component.literal("  設定するには: /quest guide set <URL>").withStyle(ChatFormatting.GRAY));
			}
			return;
		}
		out.accept(Texts.info("クエストボードの使い方（図入り）: ").append(Texts.link(guide)));
		out.accept(Component.literal("  URL をクリックするとブラウザで開きます。コマンドの一覧は /quest help").withStyle(ChatFormatting.GRAY));
	}

	/** 文字列を URL として読む。http / https の正しい URL でなければ null */
	public static java.net.URI parseUrl(String url) {
		if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
			return null;
		}
		try {
			java.net.URI uri = new java.net.URI(url);
			return uri.getHost() != null ? uri : null;
		} catch (java.net.URISyntaxException e) {
			return null;
		}
	}

	private static void section(Consumer<Component> out, String title) {
		out.accept(Component.literal(title).withStyle(ChatFormatting.YELLOW));
	}

	private static void line(Consumer<Component> out, String command, String description) {
		out.accept(Component.literal(command).withStyle(ChatFormatting.AQUA)
				.append(Component.literal("  " + description).withStyle(ChatFormatting.GRAY)));
	}

	private static String chestText(Quest quest) {
		return quest.chest != null ? quest.chestLabel() : "未設定（依頼主が /quest chest " + quest.id + " で設定）";
	}
}
