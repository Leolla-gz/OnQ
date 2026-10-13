package com.questboard.model;

/**
 * 達成済みクエストの品物の「取り置き」。
 * <p>
 * 納品チェストを複数のクエストで共有しているとき、達成したクエストの品物はチェストに残る。
 * そのままだと同じ品物が次のクエストの納品にも数えられてしまうので、
 * 依頼主が取り出すまでは「取り置き分」として、ほかのクエストの納品数から外す。
 */
public final class Reservation {
	/** 取り置いているチェストの場所 */
	public BlockLocation chest;
	/** 取り置いている品物（count = 取り置き数。チェストから減ると一緒に減る） */
	public Requirement item;

	/** Gson（JSON 読み込み）用の空コンストラクタ */
	public Reservation() {
	}

	public Reservation(BlockLocation chest, Requirement item) {
		this.chest = chest;
		this.item = item;
	}
}
