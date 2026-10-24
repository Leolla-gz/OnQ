package com.questboard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * クエスト 1 件分のデータ。JSON にそのまま保存される。
 */
public final class Quest {
	/** クエスト番号（#1, #2 ...）。コマンドでの指定に使う */
	public int id;
	/** 依頼主の UUID（名前変更に強い本人識別子） */
	public String ownerUuid;
	/** 依頼主の名前（表示用） */
	public String ownerName;
	/** 作成日時（UNIXミリ秒） */
	public long createdAt;
	/** OP が延ばした期限の日数（/quest extend）。自動取り消しは発注から「14 日 + この日数」後 */
	public int extendedDays;
	/** クエスト名（何に使う物を集めているのか。例: 「拠点の壁の材料」）。無ければ null */
	public String title;
	/** 納品条件の一覧 */
	public List<Requirement> requirements = new ArrayList<>();
	/** 納品チェストの場所。未設定なら null */
	public BlockLocation chest;
	/**
	 * 納品チェストがラージチェストのとき、もう片側の場所（ラージチェストでなければ null）。
	 * 登録していた側だけが壊されたとき、残った側を納品先として引き継ぐために覚えておく
	 */
	public BlockLocation chestPartner;
	/** 納品チェストの場所のメモ（例: 「拠点前」）。無ければ null */
	public String chestMemo;
	/** 受注したプレイヤー名の一覧 */
	public List<String> acceptedBy = new ArrayList<>();
	/** 表示確認用の見本クエストか（/quest board demo で作ったもの） */
	public boolean demo;

	public Quest() {
	}

	/** クエスト名の表示用文字列。例: "【拠点の壁の材料】"。名前が無ければ空文字 */
	public String titleLabel() {
		return title != null ? "【" + title + "】" : "";
	}

	/** 納品先の表示用文字列。例: "「拠点前」(10, 64, -20) minecraft:overworld"。未設定なら「未設定」 */
	public String chestLabel() {
		if (chest == null) {
			return "未設定";
		}
		return chestMemo != null ? "「" + chestMemo + "」" + chest.describe() : chest.describe();
	}

	/** JSON 読み込み後、古いファイル等で null の項目を補う */
	public void fillDefaults() {
		if (requirements == null) {
			requirements = new ArrayList<>();
		}
		if (acceptedBy == null) {
			acceptedBy = new ArrayList<>();
		}
	}
}
