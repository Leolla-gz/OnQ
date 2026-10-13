package com.questboard;

import com.questboard.command.QuestCommands;
import com.questboard.event.BlockInteractionHandler;
import com.questboard.event.BoardEntityHandler;
import com.questboard.event.PendingSelections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * mod の入口（C言語でいう main 関数に相当）。
 * Fabric が起動時に onInitialize() を 1 回呼ぶので、ここで「どのイベントで何をするか」を登録する。
 * 以降は Minecraft 側からイベント（コマンド入力・ブロック右クリック・毎tick）が来るたびに処理が動く。
 */
public final class QuestBoardMod implements ModInitializer {
	public static final String MOD_ID = "questboard";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 稼働中のサーバのクエスト管理。サーバが動いていない間は null */
	private static QuestManager manager;

	public static QuestManager manager() {
		return manager;
	}

	@Override
	public void onInitialize() {
		// /quest コマンドを登録
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
				QuestCommands.register(dispatcher, buildContext));

		// ブロック右クリック（ボード表示・チェスト選択）
		BlockInteractionHandler.register();

		// 壁のクエストボードの表示へのクリック・古い表示の掃除
		BoardEntityHandler.register();

		// サーバ起動時: 保存ファイルを読み込む
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			manager = new QuestManager(server);
			LOGGER.info("クエストボードを読み込みました（掲示中 {} 件）", manager.quests().size());
		});

		// サーバ停止時: 念のため保存して片付ける
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (manager != null) {
				manager.save();
				manager = null;
			}
			PendingSelections.clearAll();
		});

		// ログイン時: 依頼主名の更新と、不在中のお知らせ
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (manager != null) {
				manager.onPlayerJoin(handler.getPlayer());
			}
		});

		// 毎 tick（1秒に20回）: 納品チェストの確認（内部で約1秒ごとに間引く）
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (manager != null) {
				manager.onServerTick();
			}
		});
	}
}
