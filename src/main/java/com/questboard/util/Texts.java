package com.questboard.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * チャットに出すメッセージの部品をまとめたユーティリティ。
 * Component = Minecraft のチャット文字列（色やクリック動作を持てる）。
 */
public final class Texts {
	private Texts() {
	}

	/** すべてのメッセージの先頭に付ける「[クエストボード] 」 */
	public static MutableComponent prefix() {
		return Component.literal("[クエストボード] ").withStyle(ChatFormatting.GOLD);
	}

	/** 通常メッセージ（白文字） */
	public static MutableComponent info(String message) {
		return prefix().append(Component.literal(message).withStyle(ChatFormatting.WHITE));
	}

	/** 成功・お知らせ（緑文字） */
	public static MutableComponent success(String message) {
		return prefix().append(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	/** クリックするとブラウザで開く URL（下線付き）。開く前に、ゲームが「開きますか？」と確認を出す */
	public static MutableComponent link(java.net.URI uri) {
		return Component.literal(uri.toString()).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(uri))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("クリックでブラウザで開く"))));
	}

	/**
	 * クリックするとコマンドを実行するボタン風の文字列。例: [受注]
	 * @param label   表示文字
	 * @param command 実行するコマンド（先頭に / を付ける）
	 * @param hover   マウスを乗せたときの説明
	 */
	public static MutableComponent button(String label, String command, String hover) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}
}
