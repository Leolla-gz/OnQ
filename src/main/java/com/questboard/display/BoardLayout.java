package com.questboard.display;

import com.questboard.model.BlockLocation;

/**
 * ボード上の各表示物の「ワールド座標」を計算する。
 * <p>
 * 座標の考え方（ボードの正面から見て）:
 * <ul>
 *   <li>原点: 登録したブロックの「正面の面」の中心</li>
 *   <li>right: 見ている人から見て右方向</li>
 *   <li>up: 上方向</li>
 *   <li>out: 正面方向（ボードから手前へ）</li>
 * </ul>
 * 3×3 の壁の中央ブロックを登録すると、壁いっぱいに収まる大きさにしてある。
 */
final class BoardLayout {
	/** 列の間隔（ブロック） */
	private static final double COLUMN_WIDTH = 1.0;
	/** 各段の下端の高さ（原点からの上方向の距離）。0 段目 = 上段、1 段目 = 下段 */
	private static final double[] ROW_BASE = {-0.05, -1.4};

	/** ページ送りのボタンの、中央からの左右の距離（ブロック） */
	private static final double PAGE_BUTTON_OFFSET = 1.25;

	/** クリック判定の箱が壁から手前に出る厚み（ブロック）。文字（0.03）より手前にする */
	private static final double CLICK_DEPTH = 0.12;

	private final double originX;
	private final double originY;
	private final double originZ;
	/** 正面方向のベクトル（x, z）。例: 南向きなら (0, 1) */
	private final int outX;
	private final int outZ;

	BoardLayout(BlockLocation board) {
		int[] facing = facingVector(board.facing);
		this.outX = facing[0];
		this.outZ = facing[1];
		// ブロックの中心から、正面方向へ 0.5 ブロック = 正面の面の中心
		this.originX = board.x + 0.5 + outX * 0.5;
		this.originY = board.y + 0.5;
		this.originZ = board.z + 0.5 + outZ * 0.5;
	}

	/** タイトル文字の位置（文字の下端） */
	double[] title() {
		return point(0, 1.1, 0.03);
	}

	/** ページ送りのボタンの文字の位置（タイトルの左右）。next = true なら右側の「次」 */
	double[] pageButton(boolean next) {
		return point(next ? PAGE_BUTTON_OFFSET : -PAGE_BUTTON_OFFSET, 1.12, 0.03);
	}

	/** ページ送りのボタンのクリック判定の位置（幅 0.5 の箱。依頼のマスと同じく、壁から CLICK_DEPTH だけ出す） */
	double[] pageButtonClickArea(boolean next) {
		return point(next ? PAGE_BUTTON_OFFSET : -PAGE_BUTTON_OFFSET, 1.03, CLICK_DEPTH - 0.25);
	}

	/** 依頼主の顔の位置（顔の下端）。壁に貼るので、文字より少し壁寄りに置く */
	double[] face(int column, int row) {
		return point(columnOffset(column), ROW_BASE[row] + 0.5, 0.02);
	}

	/**
	 * ボードの正面を向く角度（Minecraft の yaw: 南 = 0, 西 = 90, 北 = 180, 東 = -90）。
	 * 壁に固定する表示（顔）の向きに使う
	 */
	float yaw() {
		if (outX != 0) {
			return outX > 0 ? -90f : 90f;
		}
		return outZ > 0 ? 0f : 180f;
	}

	/** 依頼概要の文字の位置（文字の下端） */
	double[] text(int column, int row) {
		return point(columnOffset(column), ROW_BASE[row] + 0.05, 0.03);
	}

	/**
	 * クリック判定の位置（当たり判定の底面の中心）。
	 * 当たり判定は幅 0.9 の箱（奥行きも 0.9）。箱の大部分を壁の中に入れ、壁から CLICK_DEPTH だけ出す。
	 * 手前に大きく出すと、斜め下から上の段やページ送りのボタンを狙ったときに、手前の箱が先に当たってしまう
	 */
	double[] clickArea(int column, int row) {
		return point(columnOffset(column), ROW_BASE[row], CLICK_DEPTH - 0.45);
	}

	/** 列番号（0,1,2）→ 左右方向の位置（-1, 0, +1 ブロック） */
	private static double columnOffset(int column) {
		return (column - 1) * COLUMN_WIDTH;
	}

	/** ボード基準の (右, 上, 手前) をワールド座標に変換する */
	private double[] point(double right, double up, double out) {
		// 正面ベクトル (outX, outZ) に対する「見ている人の右」は (outZ, -outX)
		// 例: ボードが南向き (0, 1) → 見る人は北を向いて立つ → 右は東 (1, 0)
		int rightX = outZ;
		int rightZ = -outX;
		return new double[] {
				originX + rightX * right + outX * out,
				originY + up,
				originZ + rightZ * right + outZ * out
		};
	}

	/** 保存されている向きの文字列 → (x, z) ベクトル。未設定なら南向き */
	static int[] facingVector(String facing) {
		if (facing == null) {
			return new int[] {0, 1};
		}
		return switch (facing) {
			case "north" -> new int[] {0, -1};
			case "east" -> new int[] {1, 0};
			case "west" -> new int[] {-1, 0};
			default -> new int[] {0, 1}; // south
		};
	}
}
