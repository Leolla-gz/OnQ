package com.questboard.gui;

import java.util.ArrayList;
import java.util.List;

import com.questboard.model.Quest;
import com.questboard.model.Requirement;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * クエストボードで使うアイテムの組み立て。
 * <p>
 * 新しいアイテムを登録すると参加者全員にも mod の導入が必要になるため、
 * 既存アイテム（本・プレイヤーの頭など）に「名前・説明文・目印データ」を付けて表現する。
 * これならサーバにだけ mod を入れれば、バニラのクライアントでも表示される。
 */
public final class QuestItems {
	/** ボードアイテムに付ける目印（アイテムの隠しデータ。見た目には出ない） */
	private static final String BOARD_MARKER = "questboard_board";

	private QuestItems() {
	}

	// ------------------------------------------------------------------
	// ボードアイテム（右クリックでボード画面を開く本）
	// ------------------------------------------------------------------

	public static ItemStack createBoardItem() {
		ItemStack stack = new ItemStack(Items.BOOK);
		stack.set(DataComponents.CUSTOM_NAME, plain("クエストボード", ChatFormatting.GOLD));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				plain("右クリックで掲示中のクエストを見る", ChatFormatting.GRAY))));
		// エンチャントされたようにキラキラさせて、普通の本と見分けやすくする
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag marker = new CompoundTag();
		marker.putBoolean(BOARD_MARKER, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		return stack;
	}

	/** そのアイテムがボードアイテムか（名前ではなく隠しデータで判定するので、金床での改名では偽造できない） */
	public static boolean isBoardItem(ItemStack stack) {
		if (!stack.is(Items.BOOK)) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(BOARD_MARKER);
	}

	// ------------------------------------------------------------------
	// ボード画面に並べる「依頼書」（依頼主の顔 = プレイヤーの頭）
	// ------------------------------------------------------------------

	/**
	 * 1 クエスト分の表示アイテムを作る。
	 * アイコン: 依頼主の頭（スキンの顔）、名前: 「#3【壁の材料】 Steve の依頼」、説明文: 納品物と進捗など
	 */
	public static ItemStack createQuestHead(Quest quest, int[] counts, boolean isMine) {
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		// 名前で指定すると、表示する側（クライアント）がそのプレイヤーのスキンを取得して顔を描く
		stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(quest.ownerName));
		stack.set(DataComponents.CUSTOM_NAME,
				plain("#" + quest.id + quest.titleLabel() + " " + quest.ownerName + " の依頼", ChatFormatting.YELLOW));

		List<Component> lore = new ArrayList<>();
		lore.add(plain("納品物:", ChatFormatting.WHITE));
		if (quest.requirements.isEmpty()) {
			lore.add(plain("  （まだありません）", ChatFormatting.GRAY));
		}
		for (int i = 0; i < quest.requirements.size(); i++) {
			Requirement r = quest.requirements.get(i);
			boolean done = counts != null && counts[i] >= r.count;
			String have = counts != null ? String.valueOf(counts[i]) : "?";
			lore.add(plain(done ? "  ✔ " : "  ・ ", done ? ChatFormatting.GREEN : ChatFormatting.WHITE)
					.append(r.displayName())
					.append(Component.literal(" " + have + "/" + r.count)));
		}
		lore.add(plain("納品先: " + quest.chestLabel(),ChatFormatting.GRAY));
		String accepted = quest.acceptedBy.isEmpty() ? "なし" : String.join(", ", quest.acceptedBy);
		lore.add(plain("受注者: " + accepted, ChatFormatting.GRAY));
		lore.add(Component.empty());
		if (isMine) {
			lore.add(plain("あなたの依頼です", ChatFormatting.AQUA));
		}
		lore.add(plain("左クリック: 受注(ブックマーク) / 右クリック: 詳細をチャットに表示", ChatFormatting.AQUA));
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/** ボード下段のボタン（矢印や案内）を作る */
	public static ItemStack createButton(Item item, String name, String... description) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, plain(name, ChatFormatting.GOLD));
		List<Component> lore = new ArrayList<>();
		for (String line : description) {
			lore.add(plain(line, ChatFormatting.GRAY));
		}
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/** 依頼主の顔付きのボタン（「自分の依頼」用） */
	public static ItemStack createPlayerButton(String playerName, String name, String... description) {
		ItemStack stack = createButton(Items.PLAYER_HEAD, name, description);
		stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(playerName));
		return stack;
	}

	/** 名前を Component（翻訳される文字など）で指定するボタン。例: クリエイティブのタブ名 */
	public static ItemStack createNamedButton(ItemStack icon, Component name, String... description) {
		icon.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
		return withHint(icon, description);
	}

	/** アイテムの見た目・名前はそのままに、説明文（操作の案内）だけを付けたコピーを作る */
	public static ItemStack withHint(ItemStack original, String... hint) {
		ItemStack stack = original.copy();
		List<Component> lore = new ArrayList<>();
		for (String line : hint) {
			lore.add(plain(line, ChatFormatting.AQUA));
		}
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/**
	 * 納品物 1 行分の表示アイテム（編集画面用）。名前は「石炭 × 130」。個数は名前だけで示す
	 * （重なりの数字は 64 までしか出せず、名前の個数と食い違って見えるので出さない）。
	 * タグの条件は特定のアイテムではないので、名札で表す
	 */
	public static ItemStack createRequirementItem(Requirement requirement, String... description) {
		ItemStack icon = requirement.kind == Requirement.Kind.TAG
				? new ItemStack(Items.NAME_TAG)
				: new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(requirement.id)));
		if (icon.isEmpty()) {
			icon = new ItemStack(Items.PAPER); // 今のバージョンに無いアイテム
		}
		return createNamedButton(icon,
				requirement.displayName().copy().append(Component.literal(" × " + requirement.count)), description);
	}

	/**
	 * 中身違いが何種類もあるアイテム（ポーション・エンチャントの本など）を、種類を問わない 1 個として表す。
	 * 中身なしだと「クラフト不可能なポーション」のような名前になるので、種類の名前（ポーション）を付ける
	 */
	public static ItemStack createAnyVariant(Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable(item.getDescriptionId())
				.withStyle(style -> style.withItalic(false)));
		return stack;
	}

	/**
	 * アイテム名・説明文は標準だと斜体になるので、斜体を外して色を付けた文字を作る
	 */
	private static MutableComponent plain(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(color));
	}
}
