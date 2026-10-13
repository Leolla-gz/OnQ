package com.questboard.display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * 納品チェストを見ているプレイヤーへの案内表示。
 * <p>
 * 画面上部のボスバー（エンダードラゴンの体力ゲージと同じ表示）を 1 行につき 1 本使う。
 * アクションバー（ホットバーの上の文字）は 1 行しか出せず、納品物が多いと画面からはみ出すため。
 * ボスバーはバニラの機能なので、参加者は mod 不要。見ている本人にだけ表示される。
 */
public final class ChestBars {
	/** 1 行分の内容。progress は 0.0〜1.0（ゲージの長さ） */
	public record Line(Component text, float progress, BossEvent.BossBarColor color) {
	}

	/** プレイヤー 1 人に表示中のバー */
	private record Shown(ServerPlayer player, List<ServerBossEvent> bars) {
	}

	private final Map<UUID, Shown> shownByPlayer = new HashMap<>();

	/** そのプレイヤーに lines を表示する（表示中なら中身だけ書き換える） */
	public void show(ServerPlayer player, List<Line> lines) {
		Shown shown = shownByPlayer.get(player.getUUID());
		// リスポーン等でプレイヤーの実体が作り直されたら、バーも作り直す
		if (shown != null && shown.player() != player) {
			hide(player);
			shown = null;
		}
		if (shown == null) {
			shown = new Shown(player, new ArrayList<>());
			shownByPlayer.put(player.getUUID(), shown);
		}
		List<ServerBossEvent> bars = shown.bars();
		while (bars.size() > lines.size()) {
			bars.removeLast().removeAllPlayers();
		}
		for (int i = 0; i < lines.size(); i++) {
			Line line = lines.get(i);
			if (i < bars.size()) {
				ServerBossEvent bar = bars.get(i);
				// 変わったときだけ送る（毎回送ると通信が無駄になる）
				if (!bar.getName().equals(line.text())) {
					bar.setName(line.text());
				}
				if (bar.getProgress() != line.progress()) {
					bar.setProgress(line.progress());
				}
				if (bar.getColor() != line.color()) {
					bar.setColor(line.color());
				}
			} else {
				ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), line.text(), line.color(),
						BossEvent.BossBarOverlay.PROGRESS);
				bar.setProgress(line.progress());
				bar.addPlayer(player);
				bars.add(bar);
			}
		}
	}

	/** そのプレイヤーへの表示を消す */
	public void hide(ServerPlayer player) {
		Shown shown = shownByPlayer.remove(player.getUUID());
		if (shown != null) {
			shown.bars().forEach(ServerBossEvent::removeAllPlayers);
		}
	}

	/** ログアウトしたプレイヤーの分を片付ける */
	public void retainOnline(Collection<ServerPlayer> online) {
		Set<UUID> onlineIds = new HashSet<>();
		online.forEach(p -> onlineIds.add(p.getUUID()));
		shownByPlayer.entrySet().removeIf(entry -> {
			if (onlineIds.contains(entry.getKey())) {
				return false;
			}
			entry.getValue().bars().forEach(ServerBossEvent::removeAllPlayers);
			return true;
		});
	}

	/** 全員の表示を消す */
	public void clear() {
		shownByPlayer.values().forEach(shown -> shown.bars().forEach(ServerBossEvent::removeAllPlayers));
		shownByPlayer.clear();
	}
}
