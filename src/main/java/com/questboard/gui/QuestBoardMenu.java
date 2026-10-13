package com.questboard.gui;

import java.util.Arrays;
import java.util.List;

import com.questboard.QuestBoardMod;
import com.questboard.QuestManager;
import com.questboard.command.QuestViews;
import com.questboard.model.Quest;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * クエストボード画面（ラージチェストと同じ 6 段 × 9 列の画面を流用）。
 * <pre>
 *  上 5 段（45 マス）: 依頼書（依頼主の顔）を並べる
 *  最下段           : [自分の依頼] [依頼を出す] … [前へ] [案内] [次へ]
 * </pre>
 * 画面の中身はサーバが作った「見せるだけ」のアイテムで、取り出しはすべて禁止する。
 * クリックはサーバの clicked() に届くので、そこで受注・詳細表示を行う。
 */
public final class QuestBoardMenu extends ChestMenu {
	private static final int ROWS = 6;
	private static final int QUESTS_PER_PAGE = 45;
	private static final int BOARD_SIZE = ROWS * 9;
	// 最下段のボタン位置（0 始まりのマス番号）
	private static final int SLOT_MINE = 45;
	private static final int SLOT_CREATE = 46;
	private static final int SLOT_PREV = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_NEXT = 50;

	private final ServerPlayer viewer;
	private final SimpleContainer board;
	/** 今表示しているページ（0 始まり） */
	private int page;
	/** 各マスに表示中のクエスト番号（クエストでないマスは 0） */
	private final int[] slotQuestIds = new int[QUESTS_PER_PAGE];

	private QuestBoardMenu(int containerId, ServerPlayer viewer, SimpleContainer board) {
		super(MenuType.GENERIC_9x6, containerId, viewer.getInventory(), board, ROWS);
		this.viewer = viewer;
		this.board = board;
		refresh();
	}

	/** プレイヤーにボード画面を開く */
	public static void open(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, p) -> new QuestBoardMenu(containerId, player, new SimpleContainer(BOARD_SIZE)),
				Component.literal("クエスト一覧")));
	}

	/** 画面の中身を最新のクエスト状況で作り直す */
	private void refresh() {
		QuestManager manager = QuestBoardMod.manager();
		board.clearContent();
		Arrays.fill(slotQuestIds, 0);
		if (manager == null) {
			return;
		}
		List<Quest> quests = manager.quests();
		int pageCount = Math.max(1, (quests.size() + QUESTS_PER_PAGE - 1) / QUESTS_PER_PAGE);
		page = Math.min(page, pageCount - 1);

		// 上 5 段: このページのクエストを並べる
		String myUuid = viewer.getUUID().toString();
		int start = page * QUESTS_PER_PAGE;
		for (int i = 0; i < QUESTS_PER_PAGE && start + i < quests.size(); i++) {
			Quest quest = quests.get(start + i);
			int[] counts = manager.currentOrLastCounts(quest);
			board.setItem(i, QuestItems.createQuestHead(quest, counts, quest.ownerUuid.equals(myUuid)));
			slotQuestIds[i] = quest.id;
		}

		// 最下段: 区切りのガラス板とボタン
		for (int slot = QUESTS_PER_PAGE; slot < BOARD_SIZE; slot++) {
			board.setItem(slot, QuestItems.createButton(Items.STAINED_GLASS_PANE.gray(), " "));
		}
		board.setItem(SLOT_MINE, QuestItems.createPlayerButton(viewer.getName().getString(),
				"自分の依頼", "クリックで自分が出した依頼の進捗をチャットに表示"));
		board.setItem(SLOT_CREATE, QuestItems.createButton(Items.WRITABLE_BOOK,
				"依頼を出す", "クリックで依頼するアイテムを画面から選ぶ"));
		board.setItem(SLOT_INFO, QuestItems.createButton(Items.BOOK,
				"掲示中 " + quests.size() + " 件（" + (page + 1) + "/" + pageCount + " ページ）",
				"発注: [依頼を出す] または /quest pick（画面から選ぶ）",
				"コマンドで発注: /quest create <アイテム> <個数>",
				"依頼書を左クリック: 受注(ブックマーク) / 右クリック: 詳細"));
		if (page > 0) {
			board.setItem(SLOT_PREV, QuestItems.createButton(Items.ARROW, "前のページ"));
		}
		if (page < pageCount - 1) {
			board.setItem(SLOT_NEXT, QuestItems.createButton(Items.ARROW, "次のページ"));
		}
	}

	/**
	 * 画面内のクリック処理。
	 * @param slotId   クリックされたマス（0〜53 がボード、54 以降は自分の持ち物）
	 * @param button   0 = 左クリック, 1 = 右クリック
	 * @param clickType クリックの種類（普通のクリック、Shift クリック、数字キー等）
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		boolean onBoard = slotId >= 0 && slotId < BOARD_SIZE;
		// 自分の持ち物欄での普通の操作だけ許可する。
		// Shift クリック・ダブルクリック・ドラッグは持ち物がボードへ入る/ボードから抜けるので禁止
		boolean safeInventoryClick = !onBoard && (clickType == ContainerInput.PICKUP || clickType == ContainerInput.SWAP
				|| clickType == ContainerInput.THROW || clickType == ContainerInput.CLONE);
		if (safeInventoryClick) {
			super.clicked(slotId, button, clickType, player);
			return;
		}
		if (onBoard && clickType == ContainerInput.PICKUP) {
			handleBoardClick(slotId, button);
		}
		// クライアント側の見た目（カーソルに乗ったアイテム等）をサーバの状態に戻す
		// （クリックで画面を閉じた・別の画面に切り替えた後は不要）
		if (viewer.containerMenu == this) {
			sendAllDataToRemote();
		}
	}

	/** Shift クリックでのアイテム移動を無効化 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	private void handleBoardClick(int slotId, int button) {
		QuestManager manager = QuestBoardMod.manager();
		if (manager == null) {
			return;
		}
		if (slotId < QUESTS_PER_PAGE) {
			int questId = slotQuestIds[slotId];
			if (questId == 0) {
				return;
			}
			try {
				Quest quest = manager.getQuest(questId);
				if (button == 1) {
					// 右クリック: 画面を閉じて詳細をチャットへ（画面を開いたままだとチャットが読みにくい）
					viewer.closeContainer();
					QuestViews.sendInfo(viewer::sendSystemMessage, manager, quest);
					return;
				}
				boolean added = manager.accept(quest, viewer);
				viewer.sendSystemMessage(added
						? Texts.success("クエスト #" + quest.id + " を受注(ブックマーク)しました")
						: Texts.info("クエスト #" + quest.id + " は受注(ブックマーク)済みです"));
			} catch (QuestException e) {
				viewer.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
			}
			refresh();
			return;
		}
		switch (slotId) {
			case SLOT_MINE -> {
				viewer.closeContainer();
				QuestViews.sendMine(viewer::sendSystemMessage, manager, viewer);
			}
			case SLOT_CREATE -> ItemPickerMenu.open(viewer, 0);
			case SLOT_PREV -> {
				page = Math.max(0, page - 1);
				refresh();
			}
			case SLOT_NEXT -> {
				page++;
				refresh();
			}
			default -> {
			}
		}
	}

	/** どこからでも開ける画面なので、距離による自動クローズはしない */
	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
