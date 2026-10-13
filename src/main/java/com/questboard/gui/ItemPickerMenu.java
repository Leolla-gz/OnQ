package com.questboard.gui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.questboard.QuestBoardMod;
import com.questboard.QuestManager;
import com.questboard.command.QuestCommands;
import com.questboard.event.PendingSelections;
import com.questboard.model.Quest;
import com.questboard.model.Requirement;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 依頼するアイテムを画面から選ぶ（アイテムの名前・ID を覚えていなくても発注できる）。
 * クエストボード画面と同じく、6 段 × 9 列のチェスト画面を流用する。
 * <pre>
 *  0. 納品物  : 今の納品物の一覧（編集画面）。クリックで削除、[アイテムを追加]、[終了]
 *  1. 分類    : クリエイティブモードのタブ（建築ブロック・材料など）を選ぶ
 *  2. アイテム: その分類のアイテムを 45 個ずつ表示。[戻る] [前へ] [次へ]
 *  3. 個数    : [-64] [-10] [-1] [+1] [+10] [+64] で決めて [決定]。
 *               上 3 段に、その個数をスモールチェストに入れたときの様子を並べる
 * </pre>
 * 新しく発注するときは 1 から始まり、[決定] でクエストができたら 0 の編集画面になる。
 * 既にあるクエスト（/quest pick 番号）は 0 から始まる。
 * アイテム名・分類名は翻訳キーのまま送るので、見る人のゲーム言語で表示される。
 */
public final class ItemPickerMenu extends ChestMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int ITEMS_PER_PAGE = 45;
	// 最下段のボタン位置（0 始まりのマス番号）
	private static final int SLOT_BACK = 45;
	private static final int SLOT_PREV = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_NEXT = 50;
	private static final int SLOT_CONFIRM = 53;
	// 編集画面（納品物の一覧）の配置
	private static final int SLOT_ADD = 45;
	private static final int SLOT_FINISH = 53;
	// 個数画面の配置
	private static final int SLOT_COUNT = 40;
	private static final int[] STEP_SLOTS = {37, 38, 39, 41, 42, 43};
	private static final int[] STEP_AMOUNTS = {-64, -10, -1, 1, 10, 64};

	private enum Screen { SUMMARY, CATEGORIES, ITEMS, COUNT }

	/** 分類 1 つ分（名前・アイコン・中のアイテム）。アイテムは同じ種類を 1 つにまとめてある */
	private record Category(Component name, ItemStack icon, List<ItemStack> items) {
	}

	// 分類の中身はサーバが動いている間は変わらないので、最初に開いたときに作って使い回す
	private static WeakReference<MinecraftServer> cachedServer = new WeakReference<>(null);
	private static List<Category> cachedCategories = List.of();

	private final ServerPlayer viewer;
	private final SimpleContainer slots;
	/** 納品物を追加するクエスト番号（0 = 新しく発注） */
	private final int targetQuestId;
	private final List<Category> categories;
	private Screen screen;
	/** 編集画面の各マスに表示中の納品物（納品物でないマスは null） */
	private final String[] slotRequirementKeys = new String[ITEMS_PER_PAGE];
	private Category category;
	/** アイテム画面で今表示しているページ（0 始まり） */
	private int page;
	private Item selected;
	private int count = 1;
	/** 選んだアイテムを登録できる最大個数（納品物の全部がスモールチェスト 1 個に収まる数） */
	private int maxCount = 1;

	private ItemPickerMenu(int containerId, ServerPlayer viewer, SimpleContainer slots, int targetQuestId) {
		super(MenuType.GENERIC_9x6, containerId, viewer.getInventory(), slots, ROWS);
		this.viewer = viewer;
		this.slots = slots;
		this.targetQuestId = targetQuestId;
		this.categories = loadCategories(viewer);
		this.screen = targetQuestId == 0 ? Screen.CATEGORIES : Screen.SUMMARY;
		refresh();
	}

	/**
	 * プレイヤーにアイテム選択画面を開く。
	 * @param targetQuestId 納品物を編集するクエスト番号（編集画面から始まる）。0 なら新しく発注する
	 */
	public static void open(ServerPlayer player, int targetQuestId) {
		String title = targetQuestId == 0 ? "依頼するアイテムを選ぶ" : "クエスト #" + targetQuestId + " の納品物";
		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, p) -> new ItemPickerMenu(containerId, player, new SimpleContainer(SIZE), targetQuestId),
				Component.literal(title)));
	}

	// ------------------------------------------------------------------
	// 分類とアイテムの一覧
	// ------------------------------------------------------------------

	/**
	 * クリエイティブモードのタブから、分類とアイテムの一覧を作る。
	 * タブの中身は普段クライアントが画面を開くときに作るもので、サーバでは空なので、ここで作る。
	 */
	private static List<Category> loadCategories(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (cachedServer.get() == server) {
			return cachedCategories;
		}
		// false = OP 用のタブ（コマンドブロック等）は作らない
		CreativeModeTabs.tryRebuildTabContents(player.level().enabledFeatures(), false, player.level().registryAccess());
		List<Category> result = new ArrayList<>();
		for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
			Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
			String path = id != null ? id.getPath() : "";
			// 検索・持ち物などの特殊なタブと、サバイバルで手に入らない物のタブは出さない
			if (tab.getType() != CreativeModeTab.Type.CATEGORY || path.equals("op_blocks") || path.equals("spawn_eggs")) {
				continue;
			}
			List<ItemStack> items = itemsOf(tab);
			if (!items.isEmpty()) {
				result.add(new Category(tab.getDisplayName(), tab.getIconItem().copy(), items));
			}
		}
		cachedServer = new WeakReference<>(server);
		cachedCategories = List.copyOf(result);
		return cachedCategories;
	}

	/**
	 * タブのアイテムを、同じ種類を 1 つにまとめて並べる。
	 * 納品条件はアイテムの種類だけを見るので、ポーションのように中身違いが並ぶ物は 1 個にまとめ、
	 * 名前も種類の名前（例: ポーション）にする
	 */
	private static List<ItemStack> itemsOf(CreativeModeTab tab) {
		Set<Item> seen = new LinkedHashSet<>();
		Set<Item> variants = new HashSet<>();
		for (ItemStack stack : tab.getDisplayItems()) {
			if (!stack.isEmpty() && !seen.add(stack.getItem())) {
				variants.add(stack.getItem());
			}
		}
		List<ItemStack> result = new ArrayList<>();
		for (Item item : seen) {
			result.add(variants.contains(item) ? QuestItems.createAnyVariant(item) : new ItemStack(item));
		}
		return result;
	}

	// ------------------------------------------------------------------
	// 画面の組み立て
	// ------------------------------------------------------------------

	/** 今の段階に合わせて画面の中身を作り直す */
	private void refresh() {
		slots.clearContent();
		// 最下段: 区切りのガラス板（ボタンはこの上に置く）
		for (int slot = ITEMS_PER_PAGE; slot < SIZE; slot++) {
			slots.setItem(slot, QuestItems.createButton(Items.STAINED_GLASS_PANE.gray(), " "));
		}
		switch (screen) {
			case SUMMARY -> drawSummary();
			case CATEGORIES -> drawCategories();
			case ITEMS -> drawItems();
			case COUNT -> drawCount();
		}
	}

	/** 編集画面: 今の納品物を並べる。品物をクリックで削除、下段に [アイテムを追加] と [終了] */
	private void drawSummary() {
		java.util.Arrays.fill(slotRequirementKeys, null);
		slots.setItem(SLOT_FINISH, QuestItems.createButton(Items.OAK_DOOR, "終了", "編集を終えて画面を閉じる"));
		Quest quest;
		try {
			quest = ownedTarget();
		} catch (QuestException e) {
			slots.setItem(SLOT_INFO, QuestItems.createButton(Items.BARRIER, e.getMessage()));
			return;
		}
		int usedSlots = 0;
		for (int i = 0; i < quest.requirements.size() && i < ITEMS_PER_PAGE; i++) {
			Requirement r = quest.requirements.get(i);
			slots.setItem(i, QuestItems.createRequirementItem(r, "クリックで削除"));
			slotRequirementKeys[i] = r.key();
			usedSlots += r.slotsNeeded();
		}
		slots.setItem(SLOT_ADD, QuestItems.createButton(Items.WRITABLE_BOOK, "アイテムを追加", "分類からアイテムを選んで納品物に追加する"));
		slots.setItem(SLOT_INFO, QuestItems.createButton(Items.BOOK,
				"クエスト #" + quest.id + " の納品物（" + quest.requirements.size() + "/" + QuestManager.MAX_REQUIREMENTS + " 種類）",
				"使用マス: " + usedSlots + "/" + QuestManager.CHEST_SLOTS + "（スモールチェスト 1 個に収まる数まで）",
				"品物をクリック: 削除",
				"納品先: " + quest.chestLabel(),
				quest.title != null ? "クエスト名: " + quest.title : "クエスト名を付ける: /quest name " + quest.id + " <名前>"));
		slots.setItem(SLOT_FINISH, QuestItems.createButton(Items.OAK_DOOR, "終了", "編集を終えて画面を閉じる",
				quest.chest == null ? "閉じた後、納品用のチェストを右クリックして設定します" : "納品先: " + quest.chestLabel()));
	}

	private void drawCategories() {
		for (int i = 0; i < categories.size() && i < ITEMS_PER_PAGE; i++) {
			Category c = categories.get(i);
			slots.setItem(i, QuestItems.createNamedButton(c.icon().copy(), c.name(), "クリックで開く"));
		}
		slots.setItem(SLOT_INFO, QuestItems.createButton(Items.BOOK, "分類を選んでください",
				"見本を持っているなら: /quest create hand [<個数>]",
				"「板材なら何でも」などは: /quest addtag <番号> <タグ> <個数>"));
		if (targetQuestId != 0) {
			slots.setItem(SLOT_BACK, QuestItems.createButton(Items.ARROW, "納品物の一覧に戻る"));
		}
		slots.setItem(SLOT_FINISH, QuestItems.createButton(Items.OAK_DOOR, targetQuestId == 0 ? "やめる" : "終了",
				targetQuestId == 0 ? "発注せずに画面を閉じる" : "編集を終えて画面を閉じる"));
	}

	private void drawItems() {
		List<ItemStack> items = category.items();
		int pageCount = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
		page = Math.clamp(page, 0, pageCount - 1);
		int start = page * ITEMS_PER_PAGE;
		for (int i = 0; i < ITEMS_PER_PAGE && start + i < items.size(); i++) {
			slots.setItem(i, QuestItems.withHint(items.get(start + i), "クリックで選ぶ"));
		}
		slots.setItem(SLOT_BACK, QuestItems.createButton(Items.ARROW, "分類に戻る"));
		slots.setItem(SLOT_INFO, QuestItems.createNamedButton(new ItemStack(Items.BOOK), category.name(),
				items.size() + " 種類（" + (page + 1) + "/" + pageCount + " ページ）"));
		if (page > 0) {
			slots.setItem(SLOT_PREV, QuestItems.createButton(Items.ARROW, "前のページ"));
		}
		if (page < pageCount - 1) {
			slots.setItem(SLOT_NEXT, QuestItems.createButton(Items.ARROW, "次のページ"));
		}
	}

	private void drawCount() {
		// 上 3 段（= スモールチェストと同じ 27 マス）に、その個数を入れたときの様子を並べる。
		// 1 マスに表示できる数は重ねられる上限（64 など）までなので、超えた分は次のマスに置く
		ItemStack shown = QuestItems.withHint(selectedStack(), "個数: " + count);
		int stackSize = shown.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
		int left = count;
		for (int slot = 0; slot < QuestManager.CHEST_SLOTS && left > 0; slot++) {
			slots.setItem(slot, shown.copyWithCount(Math.min(left, stackSize)));
			left -= stackSize;
		}

		for (int i = 0; i < STEP_SLOTS.length; i++) {
			int amount = STEP_AMOUNTS[i];
			ItemStack button = QuestItems.createButton(
					amount < 0 ? Items.STAINED_GLASS_PANE.red() : Items.STAINED_GLASS_PANE.lime(),
					(amount > 0 ? "+" : "") + amount);
			button.setCount(Math.abs(amount));
			slots.setItem(STEP_SLOTS[i], button);
		}
		String breakdown = stackSize > 1 && count >= stackSize
				? "= " + (count / stackSize) + " スタック" + (count % stackSize > 0 ? " + " + (count % stackSize) + " 個" : "")
				: "= " + count + " 個";
		slots.setItem(SLOT_COUNT, QuestItems.createButton(Items.PAPER, "個数: " + count, breakdown,
				"最大 " + maxCount + " 個（スモールチェスト 1 個に収まる数まで）"));
		slots.setItem(SLOT_BACK, QuestItems.createButton(Items.ARROW, "アイテム選びに戻る"));
		slots.setItem(SLOT_CONFIRM, QuestItems.createButton(Items.EMERALD, "決定",
				targetQuestId == 0 ? "この内容で発注する" : "クエスト #" + targetQuestId + " の納品物に追加する"));
	}

	/** 選んだアイテムの表示用スタック（一覧に出していたものと同じ見た目） */
	private ItemStack selectedStack() {
		for (ItemStack stack : category.items()) {
			if (stack.is(selected)) {
				return stack;
			}
		}
		return new ItemStack(selected);
	}

	// ------------------------------------------------------------------
	// クリック処理
	// ------------------------------------------------------------------

	/**
	 * 画面内のクリック処理（考え方は QuestBoardMenu と同じ）。
	 * @param slotId 0〜53 がこの画面、54 以降は自分の持ち物
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		boolean onMenu = slotId >= 0 && slotId < SIZE;
		// 自分の持ち物欄での普通の操作だけ許可する（Shift クリック等は持ち物が画面側へ入るので禁止）
		boolean safeInventoryClick = !onMenu && (clickType == ContainerInput.PICKUP || clickType == ContainerInput.SWAP
				|| clickType == ContainerInput.THROW || clickType == ContainerInput.CLONE);
		if (safeInventoryClick) {
			super.clicked(slotId, button, clickType, player);
			return;
		}
		if (onMenu && clickType == ContainerInput.PICKUP) {
			handleClick(slotId);
		}
		// クライアント側の見た目をサーバの状態に戻す（[決定] で画面を閉じた後は不要）
		if (viewer.containerMenu == this) {
			sendAllDataToRemote();
		}
	}

	/** Shift クリックでのアイテム移動を無効化 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	private void handleClick(int slotId) {
		switch (screen) {
			case SUMMARY -> {
				if (slotId == SLOT_FINISH) {
					viewer.closeContainer();
					return;
				} else if (slotId == SLOT_ADD) {
					screen = Screen.CATEGORIES;
				} else if (slotId < ITEMS_PER_PAGE && slotRequirementKeys[slotId] != null) {
					removeRequirement(slotRequirementKeys[slotId]);
					return;
				}
			}
			case CATEGORIES -> {
				if (slotId < categories.size() && slotId < ITEMS_PER_PAGE) {
					category = categories.get(slotId);
					page = 0;
					screen = Screen.ITEMS;
				} else if (slotId == SLOT_FINISH) {
					viewer.closeContainer();
					return;
				} else if (slotId == SLOT_BACK && targetQuestId != 0) {
					screen = Screen.SUMMARY;
				}
			}
			case ITEMS -> {
				int index = page * ITEMS_PER_PAGE + slotId;
				if (slotId < ITEMS_PER_PAGE) {
					if (index >= category.items().size()) {
						return;
					}
					if (!select(category.items().get(index).getItem())) {
						return;
					}
				} else if (slotId == SLOT_BACK) {
					screen = Screen.CATEGORIES;
				} else if (slotId == SLOT_PREV) {
					page--;
				} else if (slotId == SLOT_NEXT) {
					page++;
				}
			}
			case COUNT -> {
				if (slotId == SLOT_BACK) {
					screen = Screen.ITEMS;
				} else if (slotId == SLOT_CONFIRM) {
					confirm();
					return;
				}
				for (int i = 0; i < STEP_SLOTS.length; i++) {
					if (slotId == STEP_SLOTS[i]) {
						count = Math.clamp(count + STEP_AMOUNTS[i], 1, maxCount);
					}
				}
			}
		}
		refresh();
	}

	/** アイテムを選んで個数画面へ進む。もう登録できない（チェストに入りきらない）ときは false */
	private boolean select(Item item) {
		QuestManager manager = QuestBoardMod.manager();
		if (manager == null) {
			return false;
		}
		try {
			Quest target = targetQuestId == 0 ? null : ownedTarget();
			int addable = QuestManager.maxAddable(target, Requirement.ofItem(item, 1));
			if (addable < 1) {
				viewer.sendSystemMessage(Component.literal("クエスト #" + targetQuestId + " の納品物はスモールチェスト 1 個（"
						+ QuestManager.CHEST_SLOTS + " マス）分に達しているため、これ以上追加できません").withStyle(ChatFormatting.RED));
				return false;
			}
			selected = item;
			count = 1;
			maxCount = addable;
			screen = Screen.COUNT;
			return true;
		} catch (QuestException e) {
			viewer.closeContainer();
			viewer.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
			return false;
		}
	}

	/** 編集中のクエストを取り出す（依頼主本人であることも確認する） */
	private Quest ownedTarget() throws QuestException {
		QuestManager manager = QuestBoardMod.manager();
		if (manager == null) {
			throw new QuestException("クエストボードはまだ準備中です");
		}
		Quest quest = manager.getQuest(targetQuestId);
		if (!quest.ownerUuid.equals(viewer.getUUID().toString())) {
			throw new QuestException("クエスト #" + quest.id + " の依頼主ではないため操作できません");
		}
		return quest;
	}

	/** 編集画面で品物をクリック: その納品物を取り消す */
	private void removeRequirement(String key) {
		try {
			Quest quest = ownedTarget();
			Requirement removed = QuestBoardMod.manager().removeRequirement(quest, key);
			viewer.sendSystemMessage(Texts.success("クエスト #" + quest.id + " の納品物から ")
					.append(removed.displayName())
					.append(Component.literal(" × " + removed.count + " を削除しました")));
			refresh();
		} catch (QuestException e) {
			viewer.closeContainer();
			viewer.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	/** [決定]: 新しく発注する、または既存のクエストに納品物を追加する。どちらも、その後は編集画面になる */
	private void confirm() {
		QuestManager manager = QuestBoardMod.manager();
		if (manager == null) {
			return;
		}
		Requirement requirement = Requirement.ofItem(selected, count);
		try {
			if (targetQuestId == 0) {
				Quest quest = manager.createQuest(viewer, List.of(requirement));
				viewer.sendSystemMessage(Texts.success("クエスト #" + quest.id + " を発注しました: ")
						.append(QuestManager.progressText(quest, null))
						.append(Component.literal(" "))
						.append(Texts.button("編集", "/quest pick " + quest.id, "画面で納品物を追加・削除する")));
				// 画面の題名を「クエスト #N の納品物」にするため、編集画面として開き直す
				open(viewer, quest.id);
				return;
			}
			Quest quest = ownedTarget();
			manager.addRequirement(quest, requirement);
			viewer.sendSystemMessage(Texts.success("クエスト #" + quest.id + " の納品物: ")
					.append(QuestManager.progressText(quest, null)));
			screen = Screen.SUMMARY;
			refresh();
		} catch (QuestException e) {
			viewer.closeContainer();
			viewer.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * 画面が閉じられたとき（[終了]・Esc・[決定] 後の開き直し等）。
	 * 納品物が 0 種類なら、取り消すかを聞く（そのままでは永久に達成されないため）。
	 * 納品チェストがまだ決まっていなければ、続けてチェストを選んでもらう
	 */
	@Override
	public void removed(Player player) {
		super.removed(player);
		QuestManager manager = QuestBoardMod.manager();
		if (targetQuestId == 0 || manager == null || viewer.hasDisconnected()) {
			return;
		}
		try {
			Quest quest = ownedTarget();
			if (quest.requirements.isEmpty()) {
				viewer.sendSystemMessage(Texts.info("クエスト #" + quest.id + " の納品物が 0 種類です。このままでは達成されません。取り消しますか？ ")
						.append(Texts.button("取り消す", "/quest cancel " + quest.id, "この依頼を取り消す"))
						.append(Component.literal(" "))
						.append(Texts.button("納品物を追加する", "/quest pick " + quest.id, "編集画面を開いて納品物を追加する")));
				return;
			}
			if (quest.chest == null && PendingSelections.get(viewer.getUUID()) == null) {
				QuestCommands.startChestSelection(viewer, quest, null);
			}
		} catch (QuestException e) {
			// 編集中に達成・取り消しされた場合は何もしない
		}
	}

	/** どこからでも開ける画面なので、距離による自動クローズはしない */
	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
