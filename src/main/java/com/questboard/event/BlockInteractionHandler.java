package com.questboard.event;

import java.util.List;

import com.questboard.QuestBoardMod;
import com.questboard.QuestManager;
import com.questboard.gui.QuestBoardMenu;
import com.questboard.gui.QuestItems;
import com.questboard.model.BlockLocation;
import com.questboard.model.Quest;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 右クリックの処理。
 * 1. 選択待ち（納品チェスト・ボード登録）があれば、そのブロックを登録する
 * 2. クエストボードとして登録されたブロックなら、ボード画面を開く
 * 3. ボードアイテムを持って右クリックしたら、ボード画面を開く
 */
public final class BlockInteractionHandler {
	private BlockInteractionHandler() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register(BlockInteractionHandler::onUseBlock);
		UseItemCallback.EVENT.register(BlockInteractionHandler::onUseItem);
		// ボードにしたブロックをプレイヤーが壊したら、ボードの登録も解除する
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			QuestManager manager = QuestBoardMod.manager();
			if (manager != null && player instanceof ServerPlayer serverPlayer
					&& manager.removeBoard(BlockLocation.of(level, pos))) {
				serverPlayer.sendSystemMessage(Texts.info("クエストボードにしていたブロックが壊されたので、ボードの登録を解除しました"));
			}
		});
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		// クライアント側・オフハンド（左手）分の呼び出しは無視（1 回のクリックで 2 回処理しないため）
		if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		QuestManager manager = QuestBoardMod.manager();
		if (manager == null) {
			return InteractionResult.PASS;
		}
		BlockLocation location = BlockLocation.of(level, hit.getBlockPos());

		PendingSelections.Pending pending = PendingSelections.get(serverPlayer.getUUID());
		// 共有の確認待ち（CONFIRM_SHARE）は、チェストを右クリックしたときだけ返事として扱う
		boolean ignore = pending != null && pending.type() == PendingSelections.Type.CONFIRM_SHARE
				&& QuestManager.getContainer(level, hit.getBlockPos()) == null;
		if (pending != null && !ignore) {
			handleSelection(manager, serverPlayer, level, hit, location, pending);
			// SUCCESS を返すと、チェストが開く等の通常動作が止まる
			return InteractionResult.SUCCESS;
		}

		// スニーク（Shift）中はボードを無視して通常操作できるようにする
		if (!serverPlayer.isShiftKeyDown() && manager.isBoard(location)) {
			QuestBoardMenu.open(serverPlayer);
			return InteractionResult.SUCCESS;
		}
		// 納品チェストを開くところなら覚えておく（閉じたときに納品数を数えるため）
		manager.onChestOpened(serverPlayer, level, hit.getBlockPos());
		return InteractionResult.PASS;
	}

	/** ボードアイテム（光る本）を持って右クリック → ボード画面を開く */
	private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!QuestItems.isBoardItem(player.getItemInHand(hand)) || QuestBoardMod.manager() == null) {
			return InteractionResult.PASS;
		}
		QuestBoardMenu.open(serverPlayer);
		return InteractionResult.SUCCESS;
	}

	/**
	 * ボードの正面の向きを決める。
	 * 壁の側面をクリックしたらその面の向き、上下の面ならクリックした人の方へ向ける
	 */
	private static String boardFacing(ServerPlayer player, BlockHitResult hit) {
		Direction face = hit.getDirection();
		if (face.getStepY() != 0) {
			face = player.getDirection().getOpposite();
		}
		return switch (face) {
			case NORTH -> "north";
			case EAST -> "east";
			case WEST -> "west";
			default -> "south";
		};
	}

	/** 納品チェストを設定し、結果をプレイヤーに知らせる（右クリックでの選択と /quest share の両方から使う） */
	public static void bindChest(QuestManager manager, ServerPlayer player, Quest quest, BlockLocation location, String memo)
			throws QuestException {
		manager.bindChest(quest, location, memo);
		player.sendSystemMessage(Texts.success("クエスト #" + quest.id + " の納品チェストを " + quest.chestLabel() + " に設定しました"));
		if (quest.chestMemo == null) {
			player.sendSystemMessage(Texts.info("/quest memo " + quest.id + " <メモ> で、場所のメモ（例: 拠点前）を付けられます"));
		}
		if (quest.title == null) {
			player.sendSystemMessage(Texts.info("/quest name " + quest.id + " <名前> で、クエスト名（何に使うのか。例: 拠点の壁の材料）を付けられます"));
		}
		int existing = quest.requirements.stream().mapToInt(r -> r.baseline).sum();
		if (existing > 0) {
			player.sendSystemMessage(Texts.info("チェストに既に入っている条件に合うアイテム（" + existing
					+ " 個）は納品数に数えません。この後に入れた分だけを数えます"));
		}
	}

	/** 選んだチェストがほかのクエスト（自分のもの・ほかの人のもの）で使用中だったとき: 共有するかをチャットのボタンで選んでもらう */
	private static void askShare(ServerPlayer player, Quest quest, BlockLocation location, String memo, List<Quest> sharing) {
		PendingSelections.set(player.getUUID(), PendingSelections.Type.CONFIRM_SHARE, quest.id, memo, location);
		String others = String.join(", ", sharing.stream().map(q -> "#" + q.id + "（" + q.ownerName + "）").toList());
		player.sendSystemMessage(Texts.info("そのチェストは既にクエスト " + others + " の納品先です。まだ設定されていません。"
				+ "次のどちらかをしてください（60秒以内）"));
		player.sendSystemMessage(Component.literal("  ・共有する → 同じチェストをもう一度右クリック ").withStyle(ChatFormatting.WHITE)
				.append(Texts.button("共有する", "/quest share", "このチェストをクエスト #" + quest.id + " の納品先にもする")));
		player.sendSystemMessage(Component.literal("  ・共有しない → 別のチェストを右クリック（そちらが納品先になります）")
				.withStyle(ChatFormatting.WHITE));
		player.sendSystemMessage(Component.literal("  共有すると、同じアイテムを募集している場合は番号の若いクエストから先に数えます")
				.withStyle(ChatFormatting.GRAY));
	}

	/** 右クリックで選ばれたチェストを納品先にする。使用中のチェストなら、共有するかを聞く */
	private static void chooseChest(QuestManager manager, ServerPlayer player, PendingSelections.Pending pending,
			BlockLocation location, boolean shareConfirmed) {
		PendingSelections.clear(player.getUUID());
		try {
			Quest quest = manager.getQuest(pending.questId());
			List<Quest> sharing = manager.questsSharingChest(quest, location);
			if (sharing.isEmpty() || shareConfirmed) {
				bindChest(manager, player, quest, location, pending.memo());
			} else {
				askShare(player, quest, location, pending.memo(), sharing);
			}
		} catch (QuestException e) {
			player.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	private static void handleSelection(QuestManager manager, ServerPlayer player, Level level, BlockHitResult hit,
			BlockLocation location, PendingSelections.Pending pending) {
		switch (pending.type()) {
			case BIND_CHEST -> {
				if (QuestManager.getContainer(level, hit.getBlockPos()) == null) {
					player.sendSystemMessage(Texts.info("そのブロックは納品先にできません。チェスト・樽・シュルカーボックスを右クリックしてください"));
					return; // 選択待ちは残す
				}
				chooseChest(manager, player, pending, location, false);
			}
			case CONFIRM_SHARE -> {
				// 同じチェストをもう一度右クリック = 共有する / 別のチェスト = そちらを納品先にする
				// （チェスト以外の右クリックは onUseBlock で除外済み）
				if (manager.sameChest(location, pending.location())) {
					chooseChest(manager, player, pending, pending.location(), true);
				} else {
					chooseChest(manager, player, pending, location, false);
				}
			}
			case ADD_BOARD -> {
				PendingSelections.clear(player.getUUID());
				location.facing = boardFacing(player, hit);
				boolean added = manager.addBoard(location);
				player.sendSystemMessage(added
						? Texts.success("クエストボードを " + location.describe() + " に設置しました（数秒で表示されます）")
						: Texts.info("そこは既にクエストボードです"));
			}
			case REMOVE_BOARD -> {
				PendingSelections.clear(player.getUUID());
				boolean removed = manager.removeBoard(location);
				player.sendSystemMessage(removed
						? Texts.success("クエストボードを解除しました")
						: Texts.info("そのブロックはクエストボードではありません"));
			}
		}
	}
}
