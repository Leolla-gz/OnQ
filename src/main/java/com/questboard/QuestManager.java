package com.questboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.questboard.display.BoardDisplays;
import com.questboard.display.ChestBars;
import com.questboard.model.BlockLocation;
import com.questboard.model.Quest;
import com.questboard.model.QuestData;
import com.questboard.model.Requirement;
import com.questboard.model.Reservation;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * クエストの発注・受注・取り消し・納品判定をまとめて扱う中心クラス。
 * サーバ起動時に 1 つ作られ、停止時に破棄される。
 * <p>
 * 納品判定の考え方:
 * 納品チェストの「中身」を 1 秒ごとに数え、条件の個数がそろったら達成とする。
 * アイテムを別の場所へ移動・消去しないので、達成後は依頼主がそのチェストから受け取るだけでよい。
 */
public final class QuestManager {
	/** 1 人が同時に出せるクエスト数の上限（スパム防止） */
	public static final int MAX_OPEN_QUESTS_PER_PLAYER = 5;
	/** 1 クエストの納品条件の最大行数 */
	public static final int MAX_REQUIREMENTS = 10;
	/** 1 クエストの納品物が収まらなければならないマス数（スモールチェスト 1 個分） */
	public static final int CHEST_SLOTS = 27;
	/** 1 条件あたりの最大個数（スモールチェスト 1 個分 = 27 マス × 64）。重ねられる数が少ないアイテムはもっと少ない */
	public static final int MAX_COUNT = CHEST_SLOTS * 64;
	/**
	 * 見回りの間隔（600 tick = 約 30 秒）。
	 * 納品数は、ふだんは「納品チェストを閉じたとき」に数える。見回りは、ホッパーでの納品のように
	 * 誰もチェストを開け閉めしない変化と、チェスト・ボードが壊されていないかを拾うためのもの
	 */
	private static final int SWEEP_INTERVAL_TICKS = 600;
	/** 「納品チェストを見ているか」を調べる間隔（5 tick = 約 0.25 秒） */
	private static final int LOOK_INTERVAL_TICKS = 5;
	/** 「チェストに既に入っている分をすべて」を表す仮の値（最初から入っていた数を数えるときに使う） */
	private static final int TAKE_ALL = 1_000_000;
	/** 納品チェストを見たときに表示する行数の上限（1 行 = 画面上部のバー 1 本。多いと画面をふさぐ） */
	private static final int MAX_CHEST_BAR_LINES = 7;
	/** 発注からこの日数がたったクエストは自動で取り消す（放置された依頼がボードに残り続けないように） */
	public static final int EXPIRE_DAYS = 14;
	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	/** 期限切れを調べる間隔（見回り 2 回に 1 回 = 約 1 分） */
	private static final int EXPIRE_CHECK_INTERVAL = 2;
	/** 1 人に貯めておく不在中のお知らせの上限（古いものから捨てる） */
	private static final int MAX_PENDING_NOTICES = 20;
	/** 見本クエスト（/quest board demo）の依頼主の名前。実在のスキンがあるので、顔の表示も確認できる */
	private static final String[] DEMO_OWNERS = {"Steve", "Alex", "Notch", "jeb_", "Dinnerbone", "Grumm", "Herobrine", "Technoblade"};
	/** 見本クエストの納品物の候補 */
	private static final Item[] DEMO_ITEMS = {Items.COAL, Items.IRON_INGOT, Items.BREAD, Items.COBBLESTONE,
			Items.DIAMOND, Items.WHEAT, Items.BONE, Items.STRING, Items.ENDER_PEARL, Items.GLASS};

	private final MinecraftServer server;
	private final QuestStorage storage;
	private final QuestData data;
	/** 前回数えた納品数（変化したら依頼主に通知するため）。保存はしない */
	private final Map<Integer, int[]> lastProgress = new HashMap<>();
	/** 前回数えたチェストの中身（依頼主の取り出しを検出するため）。保存はしない */
	private final Map<Integer, int[]> lastInChest = new HashMap<>();
	/** 壁のクエストボードの表示担当 */
	private final BoardDisplays displays;
	/** 納品チェストを見ている人への案内表示の担当 */
	private final ChestBars chestBars = new ChestBars();
	/** 最初の見回りは起動の約 1 秒後に行う（壁のボードなどに進捗を出すため） */
	private int tickCounter = SWEEP_INTERVAL_TICKS - 20;
	/** 納品チェストを開いている人（プレイヤーの UUID → 開いているチェスト）。閉じたときに数えるために覚えておく */
	private final Map<UUID, ChestSession> sessions = new HashMap<>();
	/** 依頼の内容が変わったなどで、次の tick に数え直すチェスト（代表の場所） */
	private final Set<BlockLocation> dirtyChests = new HashSet<>();

	/** 納品チェストを 1 人が開いてから閉じるまで */
	private static final class ChestSession {
		final UUID player;
		/** 開いているチェストの代表の場所 */
		final BlockLocation chest;
		/** 開いている間に、ほかの人も同じチェストを開いていたか（誰が取り出したか分からなくなる） */
		boolean shared;
		/** 開いたチェストの画面（開いたのを確認できたら入る）。別の画面に切り替わったら「閉じた」とみなす */
		Object menu;

		ChestSession(UUID player, BlockLocation chest) {
			this.player = player;
			this.chest = chest;
		}
	}
	private int lookTickCounter;
	private int expireCheckCounter;

	public QuestManager(MinecraftServer server) {
		this.server = server;
		// ワールドフォルダ直下の questboard.json に保存する
		// （LevelResource のコンストラクタは private なので、用意済みの ROOT = ワールドフォルダ を使う）
		this.storage = new QuestStorage(server.getWorldPath(LevelResource.ROOT).resolve("questboard.json").normalize());
		this.data = storage.load();
		this.displays = new BoardDisplays(server);
	}

	public void save() {
		storage.save(data);
	}

	// ------------------------------------------------------------------
	// 参照系
	// ------------------------------------------------------------------

	/** 掲示中のクエスト一覧（読み取り専用で使うこと） */
	public List<Quest> quests() {
		return data.quests;
	}

	public Quest getQuest(int id) throws QuestException {
		for (Quest q : data.quests) {
			if (q.id == id) {
				return q;
			}
		}
		throw new QuestException("クエスト #" + id + " は見つかりません（達成済み・取り消し済みの可能性があります）");
	}

	/** 依頼主として出しているクエスト */
	public List<Quest> questsOwnedBy(ServerPlayer player) {
		String uuid = player.getUUID().toString();
		return data.quests.stream().filter(q -> q.ownerUuid.equals(uuid)).toList();
	}

	/** 受注しているクエスト（名前で判定） */
	public List<Quest> questsAcceptedBy(ServerPlayer player) {
		String name = player.getName().getString();
		return data.quests.stream().filter(q -> q.acceptedBy.contains(name)).toList();
	}

	public boolean isBoard(BlockLocation location) {
		return data.boards.contains(location);
	}

	public List<BlockLocation> boards() {
		return data.boards;
	}

	public BoardDisplays displays() {
		return displays;
	}

	/** 登録中のボードの識別キー一覧（表示エンティティの掃除に使う） */
	public Set<String> boardKeys() {
		Set<String> keys = new HashSet<>();
		for (BlockLocation board : data.boards) {
			keys.add(BoardDisplays.key(board));
		}
		return keys;
	}

	// ------------------------------------------------------------------
	// 発注・編集系
	// ------------------------------------------------------------------

	/** 新しいクエストを作る。納品チェストはこの後に別途設定する */
	public Quest createQuest(ServerPlayer owner, List<Requirement> requirements) throws QuestException {
		String uuid = owner.getUUID().toString();
		long openCount = data.quests.stream().filter(q -> q.ownerUuid.equals(uuid)).count();
		if (openCount >= MAX_OPEN_QUESTS_PER_PLAYER) {
			throw new QuestException("同時に出せるクエストは " + MAX_OPEN_QUESTS_PER_PLAYER + " 件までです");
		}
		Quest quest = new Quest();
		quest.ownerUuid = uuid;
		quest.ownerName = owner.getName().getString();
		quest.createdAt = System.currentTimeMillis();
		for (Requirement r : requirements) {
			mergeRequirement(quest, r);
		}
		// 番号は、個数の確認が済んでから取る（エラーで番号が飛ばないように）
		quest.id = data.nextId++;
		data.quests.add(quest);
		save();
		return quest;
	}

	/**
	 * 表示確認用の見本クエストを作る（1 人 5 件の上限があり、1 人では大量の依頼を並べられないため）。
	 * 依頼主は架空のプレイヤーで、納品チェストは無い。/quest board demo clear でまとめて消せる
	 */
	public void addDemoQuests(int count) {
		java.util.Random random = new java.util.Random();
		for (int n = 0; n < count; n++) {
			Quest quest = new Quest();
			quest.demo = true;
			quest.id = data.nextId++;
			quest.ownerName = DEMO_OWNERS[(quest.id - 1) % DEMO_OWNERS.length];
			quest.ownerUuid = UUID.nameUUIDFromBytes(("questboard-demo:" + quest.ownerName).getBytes()).toString();
			quest.createdAt = System.currentTimeMillis();
			int kinds = 1 + random.nextInt(3);
			for (int i = 0; i < kinds; i++) {
				Item item = DEMO_ITEMS[random.nextInt(DEMO_ITEMS.length)];
				Requirement requirement = Requirement.ofItem(item, 1 + random.nextInt(64));
				if (quest.requirements.stream().noneMatch(r -> r.sameTarget(requirement))) {
					quest.requirements.add(requirement);
				}
			}
			data.quests.add(quest);
		}
		save();
	}

	/** 見本クエストをすべて消す。消した件数を返す */
	public int clearDemoQuests() {
		List<Quest> demos = data.quests.stream().filter(q -> q.demo).toList();
		demos.forEach(this::cancel);
		return demos.size();
	}

	/** 使い方の説明ページの URL。未設定なら null */
	public String guideUrl() {
		return data.guideUrl;
	}

	/** 説明ページの URL を設定する（null で未設定に戻す） */
	public void setGuideUrl(String url) {
		data.guideUrl = url;
		save();
	}

	/**
	 * 次のクエスト番号を、使われていない最小の続き番号に戻す（掲示中が無ければ 1）。
	 * 掲示中のクエストと番号が重ならないよう、いちばん大きい番号の次にする
	 * @return 次に発行される番号
	 */
	public int resetNextId() {
		data.nextId = data.quests.stream().mapToInt(q -> q.id).max().orElse(0) + 1;
		save();
		return data.nextId;
	}

	/** 既存クエストに納品条件を追加する（依頼主のみ） */
	public void addRequirement(Quest quest, Requirement requirement) throws QuestException {
		int rowsBefore = quest.requirements.size();
		mergeRequirement(quest, requirement);
		lastProgress.remove(quest.id);
		forgetChestContents(quest.chest);
		if (quest.requirements.size() > rowsBefore) {
			// 新しく増えた品物: 今チェストに入っている分は納品数に数えない
			snapshotBaseline(quest, List.of(quest.requirements.getLast()));
		}
		save();
	}

	/**
	 * 納品チェストに今入っている数を「最初から入っていた数」として覚える。
	 * 納品として数えるのは、この後に増えた分だけになる
	 */
	private void snapshotBaseline(Quest quest, List<Requirement> targets) {
		// いったん「入っている分をすべて」取る設定にして数えると、今入っている数が分かる
		targets.forEach(r -> r.baseline = TAKE_ALL);
		Map<Integer, int[]> allocation = quest.chest != null ? allocate(quest.chest, false) : null;
		int[] counts = allocation != null ? allocation.get(quest.id) : null;
		for (int i = 0; i < quest.requirements.size(); i++) {
			Requirement r = quest.requirements.get(i);
			if (targets.contains(r)) {
				r.baseline = counts != null ? counts[i] : 0;
			}
		}
	}

	/**
	 * そのクエストに、この品物をあと何個まで登録できるか。
	 * 納品物の全部がスモールチェスト 1 個（27 マス）に収まる数までにする
	 * @param quest 追加先のクエスト。新しく発注するときは null
	 */
	public static int maxAddable(Quest quest, Requirement requirement) {
		int otherSlots = 0;
		int existingCount = 0;
		if (quest != null) {
			for (Requirement r : quest.requirements) {
				if (r.sameTarget(requirement)) {
					existingCount = r.count;
				} else {
					otherSlots += r.slotsNeeded();
				}
			}
		}
		return Math.max(0, (CHEST_SLOTS - otherSlots) * requirement.maxStackSize() - existingCount);
	}

	/** 同じアイテムが既にあれば個数を足し、無ければ行を追加する */
	private static void mergeRequirement(Quest quest, Requirement requirement) throws QuestException {
		int addable = maxAddable(quest, requirement);
		if (requirement.count > addable) {
			throw new QuestException("スモールチェスト 1 個（" + CHEST_SLOTS + " マス）に入りきらない個数は登録できません（"
					+ requirement.key() + " は" + (addable > 0 ? "あと " + addable + " 個まで" : "もう追加できません") + "）");
		}
		for (Requirement existing : quest.requirements) {
			if (existing.sameTarget(requirement)) {
				existing.count += requirement.count;
				return;
			}
		}
		if (quest.requirements.size() >= MAX_REQUIREMENTS) {
			throw new QuestException("納品条件は 1 クエストにつき " + MAX_REQUIREMENTS + " 種類までです");
		}
		quest.requirements.add(requirement);
	}

	/** 納品条件を 1 行取り消す（まちがえて登録したもの用。依頼主のみ） */
	public Requirement removeRequirement(Quest quest, String key) throws QuestException {
		for (Iterator<Requirement> it = quest.requirements.iterator(); it.hasNext();) {
			Requirement r = it.next();
			if (r.key().equals(key)) {
				it.remove();
				// 行がずれるので、前回数えた結果は捨てる
				lastProgress.remove(quest.id);
				lastInChest.remove(quest.id);
				forgetChestContents(quest.chest);
				save();
				return r;
			}
		}
		throw new QuestException("クエスト #" + quest.id + " の納品物に " + key + " はありません");
	}

	/**
	 * そのチェストを既に納品先にしている、ほかのクエスト（番号順）。
	 * ほかの人のクエストとも共有できる。ラージチェストは左右どちらを登録していても同じチェストとして扱う
	 */
	public List<Quest> questsSharingChest(Quest quest, BlockLocation location) {
		BlockLocation key = canonical(location);
		return data.quests.stream()
				.filter(other -> other != quest && other.chest != null && key.equals(canonical(other.chest)))
				.sorted(Comparator.comparingInt(q -> q.id))
				.toList();
	}

	/**
	 * チェストを見分けるための「代表の場所」。
	 * ラージチェストは 2 ブロックでできているので、左右どちらの場所からでも同じ値（座標の小さい側）になるようにする。
	 * 毎回その時点のブロックを見て決めるので、クエストの途中でチェストがラージチェストになった・
	 * ラージチェストが片側だけになった場合も、その時点の形に合わせて判定される
	 */
	public BlockLocation canonical(BlockLocation location) {
		ServerLevel level = findLevel(location.dimension);
		BlockPos pos = location.toBlockPos();
		if (level == null || !level.hasChunkAt(pos)) {
			return location;
		}
		BlockPos other = connectedHalf(level, pos);
		if (other == null || comparePositions(pos, other) <= 0) {
			return location;
		}
		return new BlockLocation(location.dimension, other.getX(), other.getY(), other.getZ());
	}

	/** 2 つの場所が同じチェスト（ラージチェストの左右を含む）を指しているか */
	public boolean sameChest(BlockLocation a, BlockLocation b) {
		return a != null && b != null && canonical(a).equals(canonical(b));
	}

	/** その場所がラージチェストの片側なら、もう片側の場所。そうでなければ null */
	private static BlockPos connectedHalf(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return ChestBlock.getConnectedBlockPos(pos, state);
		}
		return null;
	}

	private static int comparePositions(BlockPos a, BlockPos b) {
		if (a.getX() != b.getX()) {
			return Integer.compare(a.getX(), b.getX());
		}
		if (a.getY() != b.getY()) {
			return Integer.compare(a.getY(), b.getY());
		}
		return Integer.compare(a.getZ(), b.getZ());
	}

	/**
	 * 納品チェストを設定する。1 つのチェストを複数のクエスト（ほかの人のクエストも含む）で共有できる
	 * （共有してよいかの確認は呼び出し側で済ませておくこと）。
	 * @param memo 場所のメモ。null なら、同じチェストのままのときだけ今のメモを残す
	 */
	public void bindChest(Quest quest, BlockLocation location, String memo) {
		// ラージチェストの反対側を選び直しただけなら「同じチェスト」
		boolean sameChest = sameChest(location, quest.chest);
		if (memo != null) {
			quest.chestMemo = memo;
		} else if (quest.chest != null && !sameChest) {
			quest.chestMemo = null; // 別のチェストに変えたので、前の場所のメモは消す
		}
		forgetChestContents(quest.chest);
		quest.chest = location;
		quest.chestPartner = null; // 次の定期チェックで調べ直す
		lastProgress.remove(quest.id);
		forgetChestContents(location);
		if (!sameChest) {
			// 新しいチェストに既に入っている分は納品数に数えない（同じチェストを選び直しただけなら、納品済みの分を残す）
			snapshotBaseline(quest, quest.requirements);
			notifySharedOwners(quest);
		}
		save();
	}

	/** ほかの人のクエストの納品チェストを共有したとき、先に使っていた依頼主に知らせる（1 人につき 1 回） */
	private void notifySharedOwners(Quest quest) {
		Set<String> notified = new HashSet<>();
		for (Quest other : questsSharingChest(quest, quest.chest)) {
			if (!other.ownerUuid.equals(quest.ownerUuid) && notified.add(other.ownerUuid)) {
				notice(other, quest.ownerName + " さんのクエスト #" + quest.id + " が、あなたのクエスト #" + other.id
						+ " の納品チェスト " + other.chestLabel() + " を納品先として共有しました");
			}
		}
	}

	/** クエスト名を付ける・消す（title が null なら消す） */
	public void setTitle(Quest quest, String title) {
		quest.title = title;
		save();
	}

	/** 納品チェストのメモを付ける・消す（memo が null なら消す） */
	public void setChestMemo(Quest quest, String memo) {
		quest.chestMemo = memo;
		save();
	}

	/**
	 * そのチェストを使う全クエストの「前回の中身」を忘れる。
	 * 共有チェストでは、クエストの増減で割り当てが変わるため、
	 * それを依頼主の取り出しと取り違えないようにする
	 */
	private void forgetChestContents(BlockLocation chest) {
		if (chest == null) {
			return;
		}
		dirtyChests.add(canonical(chest)); // 表示用の納品数もすぐ数え直す
		for (Quest quest : data.quests) {
			if (sameChest(chest, quest.chest)) {
				lastInChest.remove(quest.id);
			}
		}
	}

	/** 受注する（受注者一覧に名前を載せ、依頼主に知らせる） */
	public boolean accept(Quest quest, ServerPlayer player) throws QuestException {
		// シングルプレイ（専用サーバ以外）では動作確認しやすいよう、自分のクエストも受注できる
		if (quest.ownerUuid.equals(player.getUUID().toString()) && server.isDedicatedServer()) {
			throw new QuestException("自分のクエストは受注(ブックマーク)できません");
		}
		String name = player.getName().getString();
		if (quest.acceptedBy.contains(name)) {
			return false;
		}
		quest.acceptedBy.add(name);
		save();
		notice(quest, name + " さんがクエスト #" + quest.id + " を受注(ブックマーク)しました");
		return true;
	}

	/** 取り消す（クエストを一覧から消す） */
	public void cancel(Quest quest) {
		data.quests.remove(quest);
		lastProgress.remove(quest.id);
		lastInChest.remove(quest.id);
		forgetChestContents(quest.chest);
		save();
	}

	public boolean addBoard(BlockLocation location) {
		if (data.boards.contains(location)) {
			return false;
		}
		data.boards.add(location);
		save();
		return true;
	}

	public boolean removeBoard(BlockLocation location) {
		boolean removed = data.boards.remove(location);
		if (removed) {
			save();
			displays.onBoardRemoved(this, location);
		}
		return removed;
	}

	// ------------------------------------------------------------------
	// 納品判定
	// ------------------------------------------------------------------

	/**
	 * 毎 tick 呼ばれる。
	 * 納品数を数えるのは「納品チェストが閉じられたとき」と、約 30 秒ごとの見回りのときだけ
	 * （1 個入れるたびに数えると、サーバの負荷と依頼主への通知が増えすぎるため）
	 */
	public void onServerTick() {
		displays.onServerTick(this);
		if (++lookTickCounter >= LOOK_INTERVAL_TICKS) {
			lookTickCounter = 0;
			showLookedAtChests();
			closeFinishedSessions();
		}
		recountDirtyChests();
		if (++tickCounter < SWEEP_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

		checkChests();
		checkBoards();
		if (++expireCheckCounter >= EXPIRE_CHECK_INTERVAL) {
			expireCheckCounter = 0;
			expireOldQuests();
		}
		// 見回り: 全部の納品チェストを数える（誰かが開いている最中のチェストは、閉じたときに数えるので飛ばす）
		Set<BlockLocation> done = new HashSet<>();
		for (Quest quest : List.copyOf(data.quests)) {
			if (quest.chest == null || !data.quests.contains(quest)) {
				continue;
			}
			BlockLocation key = canonical(quest.chest);
			if (done.add(key) && !isOpen(key)) {
				checkChest(quest.chest, null);
			}
		}
	}

	// ------------------------------------------------------------------
	// 納品チェストの開け閉め
	// ------------------------------------------------------------------

	/**
	 * プレイヤーがブロックを右クリックしたとき（チェストが開く直前）に呼ぶ。
	 * 納品チェストなら「開いている人」として覚え、閉じたときに納品数を数える
	 */
	public void onChestOpened(ServerPlayer player, Level level, BlockPos pos) {
		if (getContainer(level, pos) == null) {
			return;
		}
		BlockLocation location = BlockLocation.of(level, pos);
		BlockLocation key = canonical(location);
		if (data.quests.stream().noneMatch(q -> q.chest != null && key.equals(canonical(q.chest)))) {
			return;
		}
		// 前のチェストを閉じてすぐ別のチェストを開いた場合: 前の分を先に数える
		ChestSession previous = sessions.remove(player.getUUID());
		if (previous != null) {
			closeSession(previous);
		}
		ChestSession session = new ChestSession(player.getUUID(), key);
		for (ChestSession other : sessions.values()) {
			if (other.chest.equals(key)) {
				other.shared = true;
				session.shared = true;
			}
		}
		if (!session.shared) {
			// 開く前の中身を数えておく（ホッパーなどで変わっていた分を、この人の出し入れと取り違えないため）
			checkChest(location, null);
		}
		sessions.put(player.getUUID(), session);
	}

	/** チェストの画面を閉じた（またはログアウトした）人の分を数える */
	private void closeFinishedSessions() {
		if (sessions.isEmpty()) {
			return;
		}
		for (ChestSession session : List.copyOf(sessions.values())) {
			ServerPlayer player = server.getPlayerList().getPlayer(session.player);
			// 何も開いていないとき、containerMenu は持ち物の画面（inventoryMenu）になっている
			boolean closed = player == null || player.containerMenu == player.inventoryMenu
					|| (session.menu != null && player.containerMenu != session.menu);
			if (closed) {
				sessions.remove(session.player);
				closeSession(session);
			} else if (session.menu == null) {
				session.menu = player.containerMenu;
			}
		}
	}

	private void closeSession(ChestSession session) {
		dirtyChests.remove(session.chest);
		// ほかの人も開いていたなら、誰が取り出したか分からないので「依頼主の受け取り」とはみなさない
		checkChest(session.chest, session.shared ? null : session.player);
	}

	private boolean isOpen(BlockLocation chestKey) {
		for (ChestSession session : sessions.values()) {
			if (session.chest.equals(chestKey)) {
				return true;
			}
		}
		return false;
	}

	/** 依頼の内容が変わったチェストを数え直す（誰かが開いている最中なら、閉じるまで待つ） */
	private void recountDirtyChests() {
		if (dirtyChests.isEmpty()) {
			return;
		}
		for (BlockLocation chest : List.copyOf(dirtyChests)) {
			if (!isOpen(chest)) {
				dirtyChests.remove(chest);
				checkChest(chest, null);
			}
		}
	}

	/**
	 * チェスト 1 個分の納品数を数え、進捗の更新・依頼主への通知・達成の処理をする。
	 * @param closedBy このチェストを 1 人だけで開け閉めした人。その人が依頼主のクエストは、
	 *                 減った分を「依頼主の受け取り」として扱う。分からないとき・見回りのときは null
	 */
	private void checkChest(BlockLocation chest, UUID closedBy) {
		Map<Integer, int[]> allocation = allocate(chest, true);
		if (allocation == null) {
			return; // チェストが未読み込み・破壊済み
		}
		// ループ中にリストから削除すると壊れるので、達成分は後でまとめて処理する
		List<Quest> completed = new ArrayList<>();
		for (Quest quest : data.quests) {
			int[] inChest = allocation.get(quest.id);
			if (inChest == null) {
				continue; // このチェストのクエストではない
			}
			boolean ownerTook = closedBy != null && closedBy.toString().equals(quest.ownerUuid);
			recordOwnerWithdrawal(quest, inChest, ownerTook);
			int[] counts = delivered(quest, inChest);
			int[] previous = lastProgress.put(quest.id, counts);
			if (isComplete(quest, counts)) {
				completed.add(quest);
			} else if (previous != null && !Arrays.equals(previous, counts)) {
				notifyOwner(quest, Texts.info("クエスト #" + quest.id + " の納品状況: ").append(progressText(quest, counts)));
			}
		}
		for (Quest quest : completed) {
			// 共有チェストなら、達成分の品物がほかのクエストに数えられないよう取り置く
			if (allocation.size() > 1) {
				reserve(quest, allocation.get(quest.id));
			}
			complete(quest);
		}
	}

	/** 達成したクエストの品物（今チェストに入っている分）を取り置きとして記録する */
	private void reserve(Quest quest, int[] inChest) {
		for (int i = 0; i < inChest.length; i++) {
			if (inChest[i] > 0) {
				data.reservations.add(new Reservation(quest.chest, quest.requirements.get(i).withCount(inChest[i])));
			}
		}
	}

	// ------------------------------------------------------------------
	// 見回り（チェスト・ボードが壊されていないか、期限切れの依頼が無いか）
	// ------------------------------------------------------------------

	/**
	 * 納品チェストがまだあるかを調べる。
	 * <ul>
	 *   <li>ラージチェストなら、もう片側の場所を覚えておく</li>
	 *   <li>登録していた側だけが壊され、もう片側が残っていれば、残った側を納品先として引き継ぐ</li>
	 *   <li>チェストが無くなっていたら、依頼主に知らせて納品先を「未設定」に戻す（そこまでの納品数は保つ）</li>
	 * </ul>
	 * チャンクが読み込まれていない間は何もしない（壊されたのかどうか分からないため）
	 */
	private void checkChests() {
		boolean changed = false;
		for (Quest quest : data.quests) {
			if (quest.chest == null) {
				continue;
			}
			ServerLevel level = findLevel(quest.chest.dimension);
			BlockPos pos = quest.chest.toBlockPos();
			if (level == null || !level.hasChunkAt(pos)) {
				continue;
			}
			if (getContainer(level, pos) != null) {
				BlockPos other = connectedHalf(level, pos);
				BlockLocation partner = other != null ? BlockLocation.of(level, other) : null;
				if (!Objects.equals(partner, quest.chestPartner)) {
					quest.chestPartner = partner;
					changed = true;
				}
				continue;
			}
			BlockLocation partner = quest.chestPartner;
			if (partner != null) {
				BlockPos partnerPos = partner.toBlockPos();
				if (!level.hasChunkAt(partnerPos)) {
					continue; // もう片側の様子が分からないので、読み込まれるまで待つ
				}
				if (getContainer(level, partnerPos) != null) {
					// ラージチェストの登録していた側だけが壊された: 残った側を納品先にする
					for (Reservation reservation : data.reservations) {
						if (quest.chest.equals(reservation.chest)) {
							reservation.chest = partner;
						}
					}
					quest.chest = partner;
					quest.chestPartner = null;
					changed = true;
					continue;
				}
			}
			onChestLost(quest);
			changed = true;
		}
		// 無くなったチェストの取り置きは消す（中身は壊されたときにその場に落ちている）
		changed |= data.reservations.removeIf(r -> {
			ServerLevel level = findLevel(r.chest.dimension);
			BlockPos pos = r.chest.toBlockPos();
			return level != null && level.hasChunkAt(pos) && getContainer(level, pos) == null;
		});
		if (changed) {
			save();
		}
	}

	/** 納品チェストが無くなった（壊された・納品先にできないブロックに変わった）ときの処理 */
	private void onChestLost(Quest quest) {
		// そこまでの納品数は「受け取り済み」として残す（落ちた品物は依頼主が拾う想定）
		int[] last = lastProgress.get(quest.id);
		for (int i = 0; i < quest.requirements.size(); i++) {
			Requirement r = quest.requirements.get(i);
			if (last != null && i < last.length) {
				r.ownerWithdrawn = Math.max(r.ownerWithdrawn, last[i]);
			}
			r.baseline = 0;
		}
		String label = quest.chestLabel();
		quest.chest = null;
		quest.chestPartner = null;
		quest.chestMemo = null;
		lastProgress.remove(quest.id);
		lastInChest.remove(quest.id);
		notice(quest, "クエスト #" + quest.id + " の納品チェスト " + label + " が無くなりました（壊された可能性があります）。"
				+ "ここまでの納品数は残っています。/quest chest " + quest.id + " で納品チェストを設定し直してください");
	}

	/** ボードにしたブロックが無くなっていたら（爆発などで壊された）、ボードの登録を解除して表示を消す */
	private void checkBoards() {
		for (BlockLocation board : List.copyOf(data.boards)) {
			ServerLevel level = findLevel(board.dimension);
			BlockPos pos = board.toBlockPos();
			if (level != null && level.hasChunkAt(pos) && level.getBlockState(pos).isAir()) {
				removeBoard(board);
			}
		}
	}

	/** 発注から EXPIRE_DAYS 日たったクエストを取り消し、依頼主に知らせる */
	private void expireOldQuests() {
		long now = System.currentTimeMillis();
		for (Quest quest : List.copyOf(data.quests)) {
			// createdAt が 0 のもの（日時の記録が無い古いデータ）は対象にしない
			if (quest.createdAt > 0 && now >= expiresAt(quest)) {
				String chest = quest.chest != null ? "。納品チェスト " + quest.chestLabel() + " に品物が残っていれば回収してください" : "";
				cancel(quest);
				notice(quest, "クエスト #" + quest.id + " は発注から " + totalDays(quest) + " 日たったため、自動で取り消されました" + chest);
			}
		}
	}

	/** 自動取り消しになる日時（UNIX ミリ秒） */
	private static long expiresAt(Quest quest) {
		return quest.createdAt + totalDays(quest) * DAY_MS;
	}

	/** 発注から自動取り消しまでの日数（14 日 + OP が延ばした日数） */
	public static int totalDays(Quest quest) {
		return EXPIRE_DAYS + quest.extendedDays;
	}

	/**
	 * 期限を延ばす（OP 用）。依頼主に知らせる
	 * @return 延ばした後の残り日数
	 */
	public int extendExpiry(Quest quest, int days) {
		quest.extendedDays += days;
		save();
		int left = daysLeft(quest);
		notice(quest, "クエスト #" + quest.id + quest.titleLabel() + " の期限が管理者によって " + days + " 日延びました（あと " + left + " 日）");
		return left;
	}

	/** 自動取り消しまでの残り日数（切り上げ。期限を過ぎていれば 0）。日時の記録が無ければ -1 */
	public static int daysLeft(Quest quest) {
		if (quest.createdAt <= 0) {
			return -1;
		}
		long left = expiresAt(quest) - System.currentTimeMillis();
		return (int) Math.max(0, (left + DAY_MS - 1) / DAY_MS);
	}

	// ------------------------------------------------------------------
	// 納品チェストの案内表示
	// ------------------------------------------------------------------

	/**
	 * 納品チェストに視線（カーソル）を合わせているプレイヤーに、どのクエストの納品先かを
	 * 画面上部のバーで知らせる（1 行につき 1 本。見ている間だけ表示する）
	 */
	private void showLookedAtChests() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		chestBars.retainOnline(players);
		if (data.quests.stream().noneMatch(q -> q.chest != null)) {
			chestBars.clear();
			return;
		}
		for (ServerPlayer player : players) {
			// 視線の先の、手が届く範囲にあるブロックを調べる
			HitResult hit = player.pick(player.blockInteractionRange(), 1.0f, false);
			List<Quest> quests = hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
					? questsDeliveringTo(player.level(), blockHit.getBlockPos())
					: List.of();
			if (quests.isEmpty()) {
				chestBars.hide(player);
			} else {
				chestBars.show(player, chestBarLines(quests));
			}
		}
	}

	/** そのブロックを納品先にしているクエスト（番号順）。ラージチェストはどちら側を見ていても同じ結果になる */
	private List<Quest> questsDeliveringTo(Level level, BlockPos pos) {
		BlockLocation key = canonical(BlockLocation.of(level, pos));
		return data.quests.stream()
				.filter(q -> q.chest != null && key.equals(canonical(q.chest)))
				.sorted(Comparator.comparingInt(q -> q.id))
				.toList();
	}

	/**
	 * 納品チェストの案内の中身。クエストごとに見出し 1 行 + 納品物 1 行ずつ。
	 * <pre>
	 *  納品チェスト #3【壁の材料】 Steve「拠点前」   ← ゲージは全体の達成率
	 *  石炭 3/10（残り 7）               ← ゲージはその品物の納品率
	 *  樫の板材 5/5 ✔
	 * </pre>
	 */
	private List<ChestBars.Line> chestBarLines(List<Quest> quests) {
		List<ChestBars.Line> lines = new ArrayList<>();
		for (Quest quest : quests) {
			int[] counts = lastProgress.get(quest.id);
			long need = 0;
			long have = 0;
			List<ChestBars.Line> items = new ArrayList<>();
			for (int i = 0; i < quest.requirements.size(); i++) {
				Requirement r = quest.requirements.get(i);
				int count = counts != null && i < counts.length ? Math.min(counts[i], r.count) : 0;
				need += r.count;
				have += count;
				boolean done = count >= r.count;
				String progress = counts == null ? " ?/" + r.count
						: done ? " " + count + "/" + r.count + " ✔"
						: " " + count + "/" + r.count + "（残り " + (r.count - count) + "）";
				items.add(new ChestBars.Line(
						r.displayName().copy().append(Component.literal(progress))
								.withStyle(done ? ChatFormatting.GREEN : ChatFormatting.WHITE),
						(float) count / r.count,
						done ? BossEvent.BossBarColor.GREEN : BossEvent.BossBarColor.WHITE));
			}
			String memo = quest.chestMemo != null ? "「" + quest.chestMemo + "」" : "";
			lines.add(new ChestBars.Line(
					Component.literal("納品チェスト #" + quest.id + quest.titleLabel() + " " + quest.ownerName + memo)
								.withStyle(ChatFormatting.GOLD),
					need > 0 ? (float) have / need : 0f,
					BossEvent.BossBarColor.YELLOW));
			lines.addAll(items);
		}
		if (lines.size() > MAX_CHEST_BAR_LINES) {
			int hidden = lines.size() - (MAX_CHEST_BAR_LINES - 1);
			lines = new ArrayList<>(lines.subList(0, MAX_CHEST_BAR_LINES - 1));
			lines.add(new ChestBars.Line(
					Component.literal("ほか " + hidden + " 行（/quest info <番号> で全部見られます）").withStyle(ChatFormatting.GRAY),
					0f, BossEvent.BossBarColor.WHITE));
		}
		return lines;
	}

	/** 全条件の個数がそろっているか */
	public static boolean isComplete(Quest quest, int[] counts) {
		if (quest.requirements.isEmpty()) {
			return false;
		}
		for (int i = 0; i < quest.requirements.size(); i++) {
			if (counts[i] < quest.requirements.get(i).count) {
				return false;
			}
		}
		return true;
	}

	/** 達成処理: 一覧から消し、全員と依頼主に知らせる */
	private void complete(Quest quest) {
		cancel(quest);
		server.getPlayerList().broadcastSystemMessage(
				Texts.success("クエスト #" + quest.id + quest.titleLabel() + "（依頼主: " + quest.ownerName
						+ "）が達成されました！ 納品ありがとうございました"),
				false);
		notice(quest, "クエスト #" + quest.id + quest.titleLabel() + " が達成されました。納品チェスト " + quest.chestLabel() + " から品物を受け取ってください");
	}

	/**
	 * 条件ごとの納品数（= チェストの中身 + 依頼主が受け取り済みの分）。
	 * 納品チェストが未設定なら、受け取り済みの分だけ。
	 * 数えられない（チャンク未読み込み）場合は null。
	 */
	public int[] countDelivered(Quest quest) {
		if (quest.chest == null) {
			return delivered(quest, new int[quest.requirements.size()]);
		}
		int[] inChest = countInChest(quest);
		return inChest != null ? delivered(quest, inChest) : null;
	}

	/** チェストの中身に、依頼主の受け取り済み分を足す（必要数で頭打ち） */
	private static int[] delivered(Quest quest, int[] inChest) {
		int[] counts = new int[inChest.length];
		for (int i = 0; i < inChest.length; i++) {
			Requirement r = quest.requirements.get(i);
			// 最初から入っていた分は数えない
			counts[i] = Math.min(r.count, Math.max(0, inChest[i] - r.baseline) + r.ownerWithdrawn);
		}
		return counts;
	}

	/**
	 * 前回数えたときからチェストの中身が減っていて、その間にチェストを開け閉めしたのが依頼主だけなら、
	 * 減った分を「依頼主の受け取り」として記録する（＝残り個数は戻らない）。
	 * それ以外（ほかの人の取り出し・ホッパー・破壊など）で減った分は、残り個数が戻る。
	 * @param ownerTook このチェストを依頼主が 1 人だけで開け閉めした直後なら true
	 */
	private void recordOwnerWithdrawal(Quest quest, int[] inChest, boolean ownerTook) {
		int[] previous = lastInChest.put(quest.id, inChest);
		boolean comparable = previous != null && previous.length == inChest.length;
		boolean changed = false;
		for (int i = 0; i < inChest.length; i++) {
			Requirement r = quest.requirements.get(i);
			if (ownerTook && comparable && inChest[i] < previous[i]) {
				// 取り出された分のうち、納品された分（最初から入っていた分を除く）だけを受け取り済みにする
				int credit = Math.min(previous[i] - inChest[i], Math.max(0, previous[i] - r.baseline));
				if (credit > 0) {
					r.ownerWithdrawn += credit;
					changed = true;
				}
			}
			// 最初から入っていた分まで取り出されたら、「最初から入っていた数」も減らす
			if (r.baseline > inChest[i]) {
				r.baseline = inChest[i];
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}

	/**
	 * 納品チェストの中身のうち、このクエストの分を条件ごとに数える。
	 * 数えられない（チェスト未設定・チャンク未読み込み・チェストが無い）場合は null。
	 */
	private int[] countInChest(Quest quest) {
		if (quest.chest == null) {
			return null;
		}
		Map<Integer, int[]> allocation = allocate(quest.chest, false);
		return allocation != null ? allocation.get(quest.id) : null;
	}

	/**
	 * チェスト 1 個の中身を、そこを納品先にしているクエストへ割り当てる。
	 * 数えられない（チャンク未読み込み・チェストが無い）場合は null。
	 * <p>
	 * 1 つのアイテムを二重に数えないよう、次の順に取っていく。
	 * <ol>
	 *   <li>達成済みクエストの取り置き分</li>
	 *   <li>番号の若いクエストから順に（共有チェストで同じアイテムを募集しているときの優先順）</li>
	 *   <li>1 つのクエストの中では「特定アイテム」の条件を先に、「タグ」の条件を後に</li>
	 * </ol>
	 * 各条件は「必要数 − 依頼主が受け取り済みの数 + 最初から入っていた数」で頭打ちになる（余った分は次のクエストへ回る）。
	 * @param updateReservations true なら、チェストから減った分だけ取り置きを減らす（定期チェック用）
	 * @return クエスト番号 → 条件ごとの個数
	 */
	private Map<Integer, int[]> allocate(BlockLocation chest, boolean updateReservations) {
		ServerLevel level = findLevel(chest.dimension);
		if (level == null) {
			return null;
		}
		BlockPos pos = chest.toBlockPos();
		// 読み込まれていないチャンクに触るとロードが走って重くなるので避ける
		if (!level.hasChunkAt(pos)) {
			return null;
		}
		Container container = getContainer(level, pos);
		if (container == null) {
			return null; // 壊されたチェストの後始末は checkChests() が行う
		}
		// ラージチェストの左右どちらを登録していても、同じチェストとしてまとめる
		BlockLocation chestKey = canonical(chest);

		// 各スロットの、まだどこにも割り当てていない個数
		int[] remaining = new int[container.getContainerSize()];
		for (int slot = 0; slot < remaining.length; slot++) {
			remaining[slot] = container.getItem(slot).getCount();
		}

		boolean reservationsChanged = false;
		for (Iterator<Reservation> it = data.reservations.iterator(); it.hasNext();) {
			Reservation reservation = it.next();
			if (!chestKey.equals(canonical(reservation.chest))) {
				continue;
			}
			int found = take(container, remaining, reservation.item, reservation.item.count);
			if (updateReservations && found < reservation.item.count) {
				// 取り出された分は取り置きから外す（後から同じ品物が入っても、それは新しい納品）
				reservation.item.count = found;
				reservationsChanged = true;
				if (found == 0) {
					it.remove();
				}
			}
		}
		if (reservationsChanged) {
			save();
		}

		Map<Integer, int[]> result = new HashMap<>();
		List<Quest> sharing = data.quests.stream()
				.filter(q -> q.chest != null && chestKey.equals(canonical(q.chest)))
				.sorted(Comparator.comparingInt(q -> q.id))
				.toList();
		for (Quest quest : sharing) {
			List<Requirement> reqs = quest.requirements;
			int[] counts = new int[reqs.size()];
			for (Requirement.Kind kind : new Requirement.Kind[] {Requirement.Kind.ITEM, Requirement.Kind.TAG}) {
				for (int i = 0; i < reqs.size(); i++) {
					Requirement r = reqs.get(i);
					if (r.kind == kind) {
						counts[i] = take(container, remaining, r, Math.max(0, r.count - r.ownerWithdrawn + r.baseline));
					}
				}
			}
			result.put(quest.id, counts);
		}
		return result;
	}

	/** 未割り当ての中身から、条件に合うアイテムを最大 limit 個取る。取れた個数を返す */
	private static int take(Container container, int[] remaining, Requirement requirement, int limit) {
		int taken = 0;
		for (int slot = 0; slot < remaining.length && taken < limit; slot++) {
			if (remaining[slot] > 0 && requirement.matches(container.getItem(slot))) {
				int amount = Math.min(remaining[slot], limit - taken);
				remaining[slot] -= amount;
				taken += amount;
			}
		}
		return taken;
	}

	/**
	 * 最後に数えたときの納品数（チェストの中身は読まないので軽い。壁のボードのように何度も描く表示用）。
	 * まだ数えていなければ null
	 */
	public int[] lastCounts(Quest quest) {
		return quest.chest == null ? countDelivered(quest) : lastProgress.get(quest.id);
	}

	/** 最新の納品数（その場でチェストの中身を数える）。数えられなければ前回値、それも無ければ null */
	public int[] currentOrLastCounts(Quest quest) {
		int[] counts = countDelivered(quest);
		return counts != null ? counts : lastProgress.get(quest.id);
	}

	/** 例: 「石炭 3/3, 樫の板材 5/10」。counts が null なら「?/10」と表示 */
	public static Component progressText(Quest quest, int[] counts) {
		var text = Component.empty();
		for (int i = 0; i < quest.requirements.size(); i++) {
			Requirement r = quest.requirements.get(i);
			if (i > 0) {
				text.append(Component.literal(", "));
			}
			String have = counts != null ? String.valueOf(counts[i]) : "?";
			String left = counts != null ? "（残り " + (r.count - counts[i]) + "）" : "";
			text.append(r.displayName()).append(Component.literal(" " + have + "/" + r.count + left));
		}
		return text;
	}

	/**
	 * 指定位置の、納品先にできるアイテム入れ（チェスト・トラップチェスト・樽・シュルカーボックス）を返す。無ければ null。
	 * かまど・ホッパー・ディスペンサーなどは、中身が勝手に動いたり減ったりするので納品先にできない。
	 * ラージチェストの場合は 2 つ分をまとめた中身を返す。
	 */
	public static Container getContainer(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chestBlock) {
			Container chest = ChestBlock.getContainer(chestBlock, state, level, pos, true);
			if (chest != null) {
				return chest;
			}
		}
		if (!(state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
				|| state.getBlock() instanceof ShulkerBoxBlock)) {
			return null;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity instanceof Container container ? container : null;
	}

	public ServerLevel findLevel(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	/** 依頼主がオンラインならメッセージを送る（納品状況の変化など、後から見ても意味が無いもの用） */
	private void notifyOwner(Quest quest, Component message) {
		ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(quest.ownerUuid));
		if (owner != null) {
			owner.sendSystemMessage(message);
		}
	}

	/**
	 * 依頼主へのお知らせ（達成・受注・取り消しなど）。
	 * オンラインならすぐ送り、オフラインなら次にログインしたときに見せるために取っておく
	 */
	private void notice(Quest quest, String text) {
		ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(quest.ownerUuid));
		if (owner != null) {
			owner.sendSystemMessage(Texts.info(text));
			return;
		}
		if (quest.demo) {
			return; // 見本クエストの依頼主は架空なので、取っておいても読む人がいない
		}
		List<String> notices = data.pendingNotices.computeIfAbsent(quest.ownerUuid, k -> new ArrayList<>());
		if (notices.size() >= MAX_PENDING_NOTICES) {
			notices.removeFirst();
		}
		notices.add(text);
		save();
	}

	/**
	 * プレイヤーがログインしたとき:
	 * 名前が変わっていたら依頼主名を新しくし、不在中のお知らせがあれば見せる
	 */
	public void onPlayerJoin(ServerPlayer player) {
		String uuid = player.getUUID().toString();
		String name = player.getName().getString();
		boolean changed = false;
		for (Quest quest : data.quests) {
			if (quest.ownerUuid.equals(uuid) && !name.equals(quest.ownerName)) {
				quest.ownerName = name;
				changed = true;
			}
		}
		List<String> notices = data.pendingNotices.remove(uuid);
		if (notices != null && !notices.isEmpty()) {
			player.sendSystemMessage(Texts.info("不在中のお知らせ（" + notices.size() + " 件）"));
			for (String text : notices) {
				player.sendSystemMessage(Component.literal("  ・" + text).withStyle(ChatFormatting.WHITE));
			}
			changed = true;
		}
		if (changed) {
			save();
		}
	}
}
