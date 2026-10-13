package com.questboard.model;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * クエストの納品条件 1 行分。例: 「石炭 × 3」「#minecraft:planks（板材なら何でも）× 10」
 */
public final class Requirement {
	/** ITEM = 特定アイテム1種類、TAG = タグ（例: 板材全種）に含まれるアイテムなら何でも可 */
	public enum Kind { ITEM, TAG }

	public Kind kind;
	/** アイテムID（例 "minecraft:coal"）またはタグID（例 "minecraft:planks"、# は付けない） */
	public String id;
	/** 表示名の翻訳キー（例 "item.minecraft.coal"）。ITEM のときだけ使う */
	public String translationKey;
	/** 必要数 */
	public int count;
	/**
	 * 依頼主が募集中に納品チェストから取り出した数（受け取り済みの分）。
	 * チェストの中身が減っても、この分は「納品済み」のまま数える
	 */
	public int ownerWithdrawn;

	/**
	 * 納品チェストを決めた時点で、既にそのチェストに入っていた数。
	 * この分は納品数に数えない（取り出されたら、その分だけ減らす）
	 */
	public int baseline;

	/** タグ判定用のキャッシュ（JSON には保存しない＝transient） */
	private transient TagKey<Item> tagCache;

	public Requirement() {
	}

	public static Requirement ofItem(Item item, int count) {
		Requirement r = new Requirement();
		r.kind = Kind.ITEM;
		r.id = BuiltInRegistries.ITEM.getKey(item).toString();
		r.translationKey = item.getDescriptionId();
		r.count = count;
		return r;
	}

	public static Requirement ofTag(Identifier tagId, int count) {
		Requirement r = new Requirement();
		r.kind = Kind.TAG;
		r.id = tagId.toString();
		r.count = count;
		return r;
	}

	/** 同じ品物で個数だけ違う条件を作る（取り置きの記録用） */
	public Requirement withCount(int newCount) {
		Requirement r = new Requirement();
		r.kind = kind;
		r.id = id;
		r.translationKey = translationKey;
		r.count = newCount;
		return r;
	}

	/** このスタックが条件に合うアイテムかどうか */
	public boolean matches(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (kind == Kind.TAG) {
			if (tagCache == null) {
				tagCache = TagKey.create(Registries.ITEM, Identifier.parse(id));
			}
			return stack.is(tagCache);
		}
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id);
	}

	/** 条件を指し示す文字列。アイテムは ID（例 minecraft:coal）、タグは # 付き（例 #minecraft:planks） */
	public String key() {
		return (kind == Kind.TAG ? "#" : "") + id;
	}

	/** 1 マスに重ねられる数（丸石 64・エンダーパール 16・剣 1 など）。タグは中身によるので 64 とみなす */
	public int maxStackSize() {
		if (kind == Kind.TAG) {
			return Item.DEFAULT_MAX_STACK_SIZE;
		}
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(id)).getDefaultMaxStackSize();
	}

	/** この条件の品物をチェストに入れるのに必要なマス数 */
	public int slotsNeeded() {
		int stack = maxStackSize();
		return (count + stack - 1) / stack;
	}

	/** 同じ条件（同じ種類・同じID）かどうか。追加時に個数をまとめるのに使う */
	public boolean sameTarget(Requirement other) {
		return kind == other.kind && id.equals(other.id);
	}

	/** チャット表示用の名前。アイテムはプレイヤーのゲーム言語で表示される */
	public Component displayName() {
		if (kind == Kind.TAG) {
			return Component.literal("#" + id + "（いずれか）");
		}
		return translationKey != null ? Component.translatable(translationKey) : Component.literal(id);
	}
}
