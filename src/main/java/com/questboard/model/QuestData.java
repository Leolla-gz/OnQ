package com.questboard.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存ファイル（ワールドフォルダ/questboard.json）の中身全体。
 */
public final class QuestData {
	/** 次に発行するクエスト番号 */
	public int nextId = 1;
	/** 掲示中のクエスト（達成・取り消しされたものは消える） */
	public List<Quest> quests = new ArrayList<>();
	/** クエストボードとして登録されたブロックの場所 */
	public List<BlockLocation> boards = new ArrayList<>();
	/** 共有チェストに残っている、達成済みクエストの品物の取り置き */
	public List<Reservation> reservations = new ArrayList<>();
	/** 使い方の説明ページの URL（/quest guide で表示する。未設定なら null） */
	public String guideUrl;
	/** 不在中のお知らせ（プレイヤーの UUID → 次にログインしたときに見せる文）。達成・取り消しなど */
	public Map<String, List<String>> pendingNotices = new HashMap<>();

	/** JSON 読み込み後に null を補う */
	public void fillDefaults() {
		if (quests == null) {
			quests = new ArrayList<>();
		}
		if (boards == null) {
			boards = new ArrayList<>();
		}
		if (reservations == null) {
			reservations = new ArrayList<>();
		}
		if (pendingNotices == null) {
			pendingNotices = new HashMap<>();
		}
		reservations.removeIf(r -> r == null || r.chest == null || r.item == null);
		quests.forEach(Quest::fillDefaults);
	}
}
