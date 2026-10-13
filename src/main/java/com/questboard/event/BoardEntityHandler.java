package com.questboard.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.questboard.QuestBoardMod;
import com.questboard.QuestManager;
import com.questboard.command.QuestViews;
import com.questboard.model.Quest;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 壁のクエストボードに浮かぶ表示（エンティティ）への操作を処理する。
 * <ul>
 *   <li>左クリック（攻撃）: 依頼の詳細をチャットに表示</li>
 *   <li>右クリック（使用）: 依頼を受注</li>
 *   <li>読み込み時: 古い表示の掃除</li>
 * </ul>
 */
public final class BoardEntityHandler {
	/**
	 * 1 回の右クリックでサーバに 2 通の操作が届くことがあるため、
	 * 同じ人の連続クリック（5 tick = 0.25 秒以内）は無視する
	 */
	private static final int CLICK_COOLDOWN_TICKS = 5;
	private static final Map<UUID, Integer> LAST_CLICK = new HashMap<>();

	private BoardEntityHandler() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			QuestManager manager = QuestBoardMod.manager();
			if (manager != null) {
				manager.displays().onEntityLoad(entity, manager.boardKeys());
			}
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onClick(player, level, entity, false));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				hand == InteractionHand.MAIN_HAND ? onClick(player, level, entity, true) : passIfNotBoard(entity));
	}

	private static InteractionResult onClick(Player player, Level level, Entity entity, boolean isRightClick) {
		QuestManager manager = QuestBoardMod.manager();
		if (level.isClientSide() || manager == null || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		int questId = manager.displays().questIdAt(entity);
		if (questId < 0) {
			return InteractionResult.PASS; // ボードの表示ではない普通のエンティティ
		}
		if (!isDuplicateClick(serverPlayer)) {
			// ページ送りのボタンなら（左右どちらのクリックでも）ページを変える。依頼のマスなら詳細・受注
			if (!manager.displays().handlePageClick(manager, entity) && questId > 0) {
				handleQuestClick(manager, serverPlayer, questId, isRightClick);
			}
		}
		// ボードの表示に対する通常の動作（攻撃など）はさせない
		return InteractionResult.SUCCESS;
	}

	private static void handleQuestClick(QuestManager manager, ServerPlayer player, int questId, boolean isRightClick) {
		try {
			Quest quest = manager.getQuest(questId);
			if (isRightClick) {
				boolean added = manager.accept(quest, player);
				player.sendSystemMessage(added
						? Texts.success("クエスト #" + quest.id + " を受注(ブックマーク)しました。納品先: "
								+ quest.chestLabel())
						: Texts.info("クエスト #" + quest.id + " は受注(ブックマーク)済みです"));
			} else {
				QuestViews.sendInfo(player::sendSystemMessage, manager, quest);
			}
		} catch (QuestException e) {
			player.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
		}
	}

	/** オフハンド分の右クリック: ボードの表示なら握りつぶし、それ以外は通常どおり */
	private static InteractionResult passIfNotBoard(Entity entity) {
		QuestManager manager = QuestBoardMod.manager();
		return manager != null && manager.displays().questIdAt(entity) >= 0 ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	private static boolean isDuplicateClick(ServerPlayer player) {
		int now = player.level().getServer().getTickCount();
		Integer last = LAST_CLICK.put(player.getUUID(), now);
		return last != null && now - last < CLICK_COOLDOWN_TICKS;
	}
}
