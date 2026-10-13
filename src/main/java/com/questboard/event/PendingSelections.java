package com.questboard.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.questboard.model.BlockLocation;

/**
 * 「次に右クリックしたブロックを〇〇として登録する」待ち状態を、プレイヤーごとに覚えておく。
 * 例: /quest create の後 → 次に右クリックしたチェストを納品先にする。
 * サーバ再起動で消えてよい一時データなので保存しない。
 */
public final class PendingSelections {
	/** 何を選ぼうとしているか */
	public enum Type {
		/** 納品チェストを選ぶ */
		BIND_CHEST,
		/** 選んだチェストが使用中だったので、共有するかの返事（/quest share）を待つ */
		CONFIRM_SHARE,
		/** クエストボードにするブロックを選ぶ */
		ADD_BOARD,
		/** クエストボードを解除するブロックを選ぶ */
		REMOVE_BOARD
	}

	/**
	 * 選択待ちの内容。
	 * questId と memo（チェストのメモ。無ければ null）は BIND_CHEST / CONFIRM_SHARE のとき、
	 * location（選んだチェスト）は CONFIRM_SHARE のときだけ使う
	 */
	public record Pending(Type type, int questId, String memo, BlockLocation location, long expiresAt) {
	}

	/** 選択待ちの有効時間（ミリ秒） */
	public static final long TIMEOUT_MS = 60_000;

	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private PendingSelections() {
	}

	public static void set(UUID player, Type type, int questId) {
		set(player, type, questId, null, null);
	}

	public static void set(UUID player, Type type, int questId, String memo, BlockLocation location) {
		PENDING.put(player, new Pending(type, questId, memo, location, System.currentTimeMillis() + TIMEOUT_MS));
	}

	/** 有効な選択待ちを返す（期限切れなら消して null） */
	public static Pending get(UUID player) {
		Pending pending = PENDING.get(player);
		if (pending != null && pending.expiresAt() < System.currentTimeMillis()) {
			PENDING.remove(player);
			return null;
		}
		return pending;
	}

	public static void clear(UUID player) {
		PENDING.remove(player);
	}

	public static void clearAll() {
		PENDING.clear();
	}
}
