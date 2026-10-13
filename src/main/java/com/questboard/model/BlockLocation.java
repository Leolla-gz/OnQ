package com.questboard.model;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * ワールド内のブロック位置（ディメンション + 座標）。
 * 納品チェストやクエストボードの場所を JSON に保存するために使う。
 * Minecraft の BlockPos はディメンションを持たないので、文字列で一緒に持つ。
 */
public final class BlockLocation {
	/** 例: "minecraft:overworld", "minecraft:the_nether" */
	public String dimension;
	public int x;
	public int y;
	public int z;
	/**
	 * 正面の向き（"north" / "south" / "east" / "west"）。クエストボードの表示向きにだけ使う。
	 * 同じ場所かどうかの比較（equals）には含めない
	 */
	public String facing;

	/** Gson（JSON 読み込み）用の空コンストラクタ */
	public BlockLocation() {
	}

	public BlockLocation(String dimension, int x, int y, int z) {
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	/** ワールドとブロック座標から作る */
	public static BlockLocation of(Level level, BlockPos pos) {
		return new BlockLocation(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
	}

	public BlockPos toBlockPos() {
		return new BlockPos(x, y, z);
	}

	/** チャット表示用 例: "(10, 64, -20) minecraft:overworld" */
	public String describe() {
		return "(" + x + ", " + y + ", " + z + ") " + dimension;
	}

	// HashMap のキーや List.contains で「同じ場所か」を比較できるようにする
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BlockLocation other)) {
			return false;
		}
		return x == other.x && y == other.y && z == other.z && Objects.equals(dimension, other.dimension);
	}

	@Override
	public int hashCode() {
		return Objects.hash(dimension, x, y, z);
	}
}
