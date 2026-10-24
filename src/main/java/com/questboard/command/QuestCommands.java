package com.questboard.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.questboard.QuestBoardMod;
import com.questboard.QuestManager;
import com.questboard.event.BlockInteractionHandler;
import com.questboard.event.PendingSelections;
import com.questboard.gui.ItemPickerMenu;
import com.questboard.gui.QuestBoardMenu;
import com.questboard.gui.QuestItems;
import com.questboard.model.BlockLocation;
import com.questboard.model.Quest;
import com.questboard.model.Requirement;
import com.questboard.util.QuestException;
import com.questboard.util.Texts;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Items;

/**
 * /quest コマンドの定義。
 * <p>
 * Brigadier（Minecraft のコマンド解析ライブラリ）では、コマンドを木構造で組み立てる:
 * literal("quest") → literal("info") → argument("id", 整数) → executes(実行する処理)
 * 引数の型を指定しておくと、入力補完（Tab キー）とエラー表示を自動でやってくれる。
 */
public final class QuestCommands {
	/** /quest create で一度に指定できるアイテムの種類数 */
	private static final int MAX_CREATE_PAIRS = 4;
	/** 納品チェストのメモの最大文字数 */
	private static final int MAX_MEMO_LENGTH = 30;
	/** クエスト名の最大文字数（壁のボードやボード画面に収まる長さ） */
	private static final int MAX_TITLE_LENGTH = 20;

	private QuestCommands() {
	}

	/** Tab 補完で掲示中のクエスト番号を候補に出す */
	private static final SuggestionProvider<CommandSourceStack> QUEST_IDS = (context, builder) -> {
		QuestManager manager = QuestBoardMod.manager();
		if (manager != null) {
			manager.quests().forEach(q -> builder.suggest(q.id));
		}
		return builder.buildFuture();
	};

	/** Tab 補完で、そのクエストの納品物（取り消せるもの）を候補に出す */
	private static final SuggestionProvider<CommandSourceStack> REQUIREMENT_KEYS = (context, builder) -> {
		QuestManager manager = QuestBoardMod.manager();
		try {
			if (manager != null) {
				Quest quest = manager.getQuest(IntegerArgumentType.getInteger(context, "id"));
				return SharedSuggestionProvider.suggest(quest.requirements.stream().map(Requirement::key), builder);
			}
		} catch (QuestException | IllegalArgumentException e) {
			// 番号がまだ入力されていない・存在しないときは候補なし
		}
		return builder.buildFuture();
	};

	/** Tab 補完でアイテムタグ（例 minecraft:planks）を候補に出す */
	private static final SuggestionProvider<CommandSourceStack> ITEM_TAGS = (context, builder) ->
			SharedSuggestionProvider.suggestResource(
					BuiltInRegistries.ITEM.getTags().map(HolderSet.Named::key).map(TagKey::location), builder);

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("quest")
				.executes(QuestCommands::help)
				.then(Commands.literal("help")
						.executes(QuestCommands::help)
						// 管理用のコマンドは、ふつうのヘルプと分けて表示する
						.then(Commands.literal("op")
								.requires(QuestCommands::canManageBoards)
								.executes(QuestCommands::opHelp)))
				.then(Commands.literal("list")
						.executes(c -> list(c, 1))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
								.executes(c -> list(c, IntegerArgumentType.getInteger(c, "page")))))
				.then(Commands.literal("mine").executes(QuestCommands::mine))
				.then(Commands.literal("open").executes(QuestCommands::openBoard))
				.then(Commands.literal("boarditem").executes(QuestCommands::boardItem))
				.then(Commands.literal("info")
						.then(questIdArgument().executes(QuestCommands::info)))
				.then(Commands.literal("create")
						// 引数なしでも発注でき、後から add / addtag で納品物を足せる
						.executes(c -> create(c, 0))
						// hand = 手に持っているアイテム（個数を省略すると、持っている数）
						.then(Commands.literal("hand")
								.executes(c -> createFromHand(c, false))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, QuestManager.MAX_COUNT))
										.executes(c -> createFromHand(c, true))))
						.then(createPair(buildContext, 1)))
				// 画面からアイテムを選んで発注する（番号を付けると、その依頼に納品物を追加）
				.then(Commands.literal("pick")
						.executes(c -> pick(c, false))
						.then(questIdArgument().executes(c -> pick(c, true))))
				.then(Commands.literal("add")
						.then(questIdArgument()
								.then(Commands.literal("hand")
										.executes(c -> addFromHand(c, false))
										.then(Commands.argument("count", IntegerArgumentType.integer(1, QuestManager.MAX_COUNT))
												.executes(c -> addFromHand(c, true))))
								.then(Commands.argument("item", ItemArgument.item(buildContext))
										.then(Commands.argument("count", IntegerArgumentType.integer(1, QuestManager.MAX_COUNT))
												.executes(QuestCommands::addItem)))))
				.then(Commands.literal("addtag")
						.then(questIdArgument()
								.then(Commands.argument("tag", IdentifierArgument.id()).suggests(ITEM_TAGS)
										.then(Commands.argument("count", IntegerArgumentType.integer(1, QuestManager.MAX_COUNT))
												.executes(QuestCommands::addTag)))))
				// 納品物の取り消し。対象を省略すると、[削除] ボタン付きの一覧を出す
				.then(Commands.literal("remove")
						.then(questIdArgument()
								.executes(QuestCommands::listRemovable)
								.then(Commands.argument("target", StringArgumentType.greedyString()).suggests(REQUIREMENT_KEYS)
										.executes(QuestCommands::removeRequirement))))
				.then(Commands.literal("chest")
						.then(questIdArgument()
								.executes(c -> selectChest(c, false))
								// greedyString = 行末までを 1 つの文字列として受け取る（空白を含むメモも書ける）
								.then(Commands.argument("memo", StringArgumentType.greedyString())
										.executes(c -> selectChest(c, true)))))
				.then(Commands.literal("memo")
						.then(questIdArgument()
								.executes(c -> setMemo(c, false))
								.then(Commands.argument("memo", StringArgumentType.greedyString())
										.executes(c -> setMemo(c, true)))))
				// クエスト名（何に使う物を集めているのか）を付ける。省略すると消す
				.then(Commands.literal("name")
						.then(questIdArgument()
								.executes(c -> setTitle(c, false))
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(c -> setTitle(c, true)))))
				.then(Commands.literal("share").executes(QuestCommands::shareChest))
				.then(Commands.literal("cancel")
						.then(questIdArgument().executes(QuestCommands::cancel)))
				.then(Commands.literal("accept")
						.then(questIdArgument().executes(QuestCommands::accept)))
				// 使い方の説明ページの URL を表示する（set / clear は管理者だけ）
				.then(Commands.literal("guide")
						.executes(QuestCommands::guide)
						.then(Commands.literal("set")
								.requires(QuestCommands::canManageBoards)
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(QuestCommands::setGuide)))
						.then(Commands.literal("clear")
								.requires(QuestCommands::canManageBoards)
								.executes(QuestCommands::clearGuide)))
				// クエストの期限を延ばす（OP）。ボードの管理と同じ権限
				.then(Commands.literal("extend")
						.requires(QuestCommands::canManageBoards)
						.then(questIdArgument()
								.then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
										.executes(QuestCommands::extend))))
				// クエスト番号の振り直し（テストで番号が大きくなったとき用）。ボードの管理と同じ権限
				.then(Commands.literal("resetid")
						.requires(QuestCommands::canManageBoards)
						.executes(QuestCommands::resetId))
				.then(Commands.literal("board")
						// ボードの設置・解除は OP（権限レベル2以上）のみ。シングルプレイでは誰でも可
						.requires(QuestCommands::canManageBoards)
						.then(Commands.literal("add").executes(c -> selectBoard(c, PendingSelections.Type.ADD_BOARD)))
						.then(Commands.literal("remove").executes(c -> selectBoard(c, PendingSelections.Type.REMOVE_BOARD)))
						.then(Commands.literal("list").executes(QuestCommands::listBoards))
						.then(Commands.literal("refresh").executes(QuestCommands::refreshBoards))
						// 表示確認用の見本クエスト（1 人では 5 件までしか出せないため）
						.then(Commands.literal("demo")
								.then(Commands.literal("clear").executes(QuestCommands::clearDemo))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
										.executes(QuestCommands::addDemo)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> questIdArgument() {
		return Commands.argument("id", IntegerArgumentType.integer(1)).suggests(QUEST_IDS);
	}

	/**
	 * /quest create <item1> <count1> [<item2> <count2> ...] を再帰的に組み立てる。
	 * n 組目の個数まで入力された時点で実行できる（= 2 組目以降は省略可能）。
	 */
	private static RequiredArgumentBuilder<CommandSourceStack, ?> createPair(CommandBuildContext buildContext, int n) {
		RequiredArgumentBuilder<CommandSourceStack, Integer> countArg =
				Commands.argument("count" + n, IntegerArgumentType.integer(1, QuestManager.MAX_COUNT))
						.executes(c -> create(c, n));
		if (n < MAX_CREATE_PAIRS) {
			countArg.then(createPair(buildContext, n + 1));
		}
		return Commands.argument("item" + n, ItemArgument.item(buildContext)).then(countArg);
	}

	// ------------------------------------------------------------------
	// 各コマンドの処理（戻り値 1 = 成功、0 = 失敗。Minecraft の慣例）
	// ------------------------------------------------------------------

	private static int help(CommandContext<CommandSourceStack> c) {
		CommandSourceStack source = c.getSource();
		QuestManager manager = manager();
		QuestViews.sendHelp(message -> source.sendSuccess(() -> message, false), canManageBoards(source),
				manager != null ? manager.guideUrl() : null);
		return 1;
	}

	private static int opHelp(CommandContext<CommandSourceStack> c) {
		CommandSourceStack source = c.getSource();
		QuestViews.sendOpHelp(message -> source.sendSuccess(() -> message, false));
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> c, int page) {
		return run(c, source -> QuestViews.sendBoard(message -> source.sendSuccess(() -> message, false), manager(), page));
	}

	private static int mine(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> QuestViews.sendMine(message -> source.sendSuccess(() -> message, false), manager(), player));
	}

	private static int openBoard(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> QuestBoardMenu.open(player));
	}

	/**
	 * ボードアイテムを手に入れる。
	 * サバイバルで本がタダで増えないよう、手に持った本 1 冊と交換する（OP・シングルプレイは無料）。
	 */
	private static int boardItem(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> {
			if (!canManageBoards(source)) {
				ItemStack hand = player.getMainHandItem();
				if (!hand.is(Items.BOOK) || QuestItems.isBoardItem(hand)) {
					throw new QuestException("本（普通の本）を手に持って実行してください。その本がクエストボードになります");
				}
				hand.shrink(1);
			}
			// 持ち物に入れる（いっぱいなら足元に落ちる）
			player.getInventory().placeItemBackInInventory(QuestItems.createBoardItem(), Prediction.SERVER_ONLY);
			source.sendSuccess(() -> Texts.success("クエストボードを手に入れました。持って右クリックすると開けます"), false);
		});
	}

	private static int info(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			Quest quest = manager().getQuest(IntegerArgumentType.getInteger(c, "id"));
			QuestViews.sendInfo(message -> source.sendSuccess(() -> message, false), manager(), quest);
		});
	}

	private static int create(CommandContext<CommandSourceStack> c, int pairs) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		// 入力された (アイテム, 個数) の組を集める
		List<Requirement> requirements = new ArrayList<>();
		for (int i = 1; i <= pairs; i++) {
			Item item = ItemArgument.getItem(c, "item" + i).item().value();
			int count = IntegerArgumentType.getInteger(c, "count" + i);
			requirements.add(Requirement.ofItem(item, count));
		}
		return run(c, source -> createQuest(source, player, requirements));
	}

	/** /quest create hand [<個数>]: 手に持っているアイテムで発注する */
	private static int createFromHand(CommandContext<CommandSourceStack> c, boolean hasCount) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> createQuest(source, player, List.of(handRequirement(c, player, hasCount))));
	}

	private static void createQuest(CommandSourceStack source, ServerPlayer player, List<Requirement> requirements)
			throws QuestException {
		Quest quest = manager().createQuest(player, requirements);
		source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " を発注しました: ")
				.append(QuestManager.progressText(quest, null)), false);
		if (requirements.isEmpty()) {
			source.sendSuccess(() -> Texts.info("/quest add " + quest.id + " または /quest addtag " + quest.id
					+ " で納品物を追加してください"), false);
		}
		startChestSelection(player, quest, null);
	}

	/** /quest pick [<番号>]: アイテムを画面から選んで発注する・納品物を追加する */
	private static int pick(CommandContext<CommandSourceStack> c, boolean hasId) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> ItemPickerMenu.open(player, hasId ? ownedQuest(c, false).id : 0));
	}

	private static int addItem(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Item item = ItemArgument.getItem(c, "item").item().value();
		int count = IntegerArgumentType.getInteger(c, "count");
		return run(c, source -> addRequirement(c, source, Requirement.ofItem(item, count)));
	}

	/** /quest add <番号> hand [<個数>]: 手に持っているアイテムを納品物に追加する */
	private static int addFromHand(CommandContext<CommandSourceStack> c, boolean hasCount) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> addRequirement(c, source, handRequirement(c, player, hasCount)));
	}

	private static void addRequirement(CommandContext<CommandSourceStack> c, CommandSourceStack source, Requirement requirement)
			throws QuestException {
		Quest quest = ownedQuest(c, false);
		manager().addRequirement(quest, requirement);
		source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " の納品物: ")
				.append(QuestManager.progressText(quest, null)), false);
	}

	/**
	 * 手（メインハンド）に持っているアイテムを納品条件にする。
	 * アイテムの正式な名前（ID）を覚えていなくても、見本が 1 個あれば指定できる。
	 * 個数を省略したときは、手に持っている数にする
	 */
	private static Requirement handRequirement(CommandContext<CommandSourceStack> c, ServerPlayer player, boolean hasCount)
			throws QuestException {
		ItemStack hand = player.getMainHandItem();
		if (hand.isEmpty()) {
			throw new QuestException("納品してほしいアイテムを手に持って実行してください");
		}
		int count = hasCount ? IntegerArgumentType.getInteger(c, "count") : hand.getCount();
		return Requirement.ofItem(hand.getItem(), count);
	}

	private static int addTag(CommandContext<CommandSourceStack> c) {
		Identifier tagId = IdentifierArgument.getId(c, "tag");
		int count = IntegerArgumentType.getInteger(c, "count");
		return run(c, source -> {
			// 存在しないタグ（打ち間違い）だと永遠に達成できないので、ここで弾く
			TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
			if (!BuiltInRegistries.ITEM.getTagOrEmpty(tag).iterator().hasNext()) {
				throw new QuestException("アイテムタグ " + tagId + " は存在しないか、中身が空です");
			}
			Quest quest = ownedQuest(c, false);
			manager().addRequirement(quest, Requirement.ofTag(tagId, count));
			source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " の納品物: ")
					.append(QuestManager.progressText(quest, null)), false);
		});
	}

	/** /quest remove <番号>: 納品物の一覧を [削除] ボタン付きで表示する */
	private static int listRemovable(CommandContext<CommandSourceStack> c) {
		return run(c, source -> sendRemovable(source, ownedQuest(c, false)));
	}

	/** /quest remove <番号> <アイテムID または #タグ>: まちがえて登録した納品物を取り消す */
	private static int removeRequirement(CommandContext<CommandSourceStack> c) {
		String key = StringArgumentType.getString(c, "target").strip();
		return run(c, source -> {
			Quest quest = ownedQuest(c, false);
			Requirement removed = manager().removeRequirement(quest, key);
			source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " の納品物から ")
					.append(removed.displayName())
					.append(Component.literal(" × " + removed.count + " を削除しました")), false);
			sendRemovable(source, quest);
		});
	}

	private static void sendRemovable(CommandSourceStack source, Quest quest) {
		if (quest.requirements.isEmpty()) {
			source.sendSuccess(() -> Texts.info("クエスト #" + quest.id + " の納品物はありません。/quest pick " + quest.id
					+ " で追加できます"), false);
			return;
		}
		source.sendSuccess(() -> Texts.info("クエスト #" + quest.id + " の納品物（[削除] で取り消し）:"), false);
		for (Requirement r : quest.requirements) {
			source.sendSuccess(() -> Component.literal("  ・ ").append(r.displayName())
					.append(Component.literal(" × " + r.count + " "))
					.append(Texts.button("削除", "/quest remove " + quest.id + " " + r.key(), "この納品物を取り消す")), false);
		}
	}

	private static int selectChest(CommandContext<CommandSourceStack> c, boolean hasMemo) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> startChestSelection(player, ownedQuest(c, false), hasMemo ? memoArgument(c) : null));
	}

	/** /quest memo <番号> [<メモ>]: 納品チェストのメモを付ける。メモを省略すると消す */
	private static int setMemo(CommandContext<CommandSourceStack> c, boolean hasMemo) {
		return run(c, source -> {
			Quest quest = ownedQuest(c, false);
			String memo = hasMemo ? memoArgument(c) : null;
			manager().setChestMemo(quest, memo);
			source.sendSuccess(() -> Texts.success(memo != null
					? "クエスト #" + quest.id + " の納品チェストのメモを「" + memo + "」にしました"
					: "クエスト #" + quest.id + " の納品チェストのメモを消しました"), false);
		});
	}

	/** /quest name <番号> [<名前>]: クエスト名（何に使う物を集めているのか）を付ける。名前を省略すると消す */
	private static int setTitle(CommandContext<CommandSourceStack> c, boolean hasName) {
		return run(c, source -> {
			Quest quest = ownedQuest(c, false);
			String name = hasName ? StringArgumentType.getString(c, "name").strip() : "";
			if (name.length() > MAX_TITLE_LENGTH) {
				throw new QuestException("クエスト名は " + MAX_TITLE_LENGTH + " 文字までです");
			}
			String title = name.isEmpty() ? null : name;
			manager().setTitle(quest, title);
			source.sendSuccess(() -> Texts.success(title != null
					? "クエスト #" + quest.id + " の名前を【" + title + "】にしました"
					: "クエスト #" + quest.id + " の名前を消しました"), false);
		});
	}

	/** メモの引数を取り出す（前後の空白を除き、長すぎるものは弾く） */
	private static String memoArgument(CommandContext<CommandSourceStack> c) throws QuestException {
		String memo = StringArgumentType.getString(c, "memo").strip();
		if (memo.isEmpty()) {
			return null;
		}
		if (memo.length() > MAX_MEMO_LENGTH) {
			throw new QuestException("メモは " + MAX_MEMO_LENGTH + " 文字までです");
		}
		return memo;
	}

	/** /quest share: 使用中のチェストを選んだときの確認に「共有する」と答える */
	private static int shareChest(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> {
			PendingSelections.Pending pending = PendingSelections.get(player.getUUID());
			if (pending == null || pending.type() != PendingSelections.Type.CONFIRM_SHARE) {
				throw new QuestException("共有の確認待ちはありません。/quest chest <番号> でチェストを選び直してください");
			}
			PendingSelections.clear(player.getUUID());
			Quest quest = manager().getQuest(pending.questId());
			if (!quest.ownerUuid.equals(player.getUUID().toString())) {
				throw new QuestException("クエスト #" + quest.id + " の依頼主ではないため操作できません");
			}
			BlockInteractionHandler.bindChest(manager(), player, quest, pending.location(), pending.memo());
		});
	}

	private static int cancel(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			Quest quest = ownedQuest(c, true);
			manager().cancel(quest);
			source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " を取り消しました"), false);
		});
	}

	private static int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return run(c, source -> {
			Quest quest = manager().getQuest(IntegerArgumentType.getInteger(c, "id"));
			boolean added = manager().accept(quest, player);
			source.sendSuccess(() -> Texts.success((added ? "クエスト #" + quest.id + " を受注(ブックマーク)しました。" : "既に受注(ブックマーク)済みです。")
					+ "納品先のチェスト " + quest.chestLabel() + " に品物を入れてください"), false);
		});
	}

	private static int selectBoard(CommandContext<CommandSourceStack> c, PendingSelections.Type type) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		PendingSelections.set(player.getUUID(), type, 0);
		String action = type == PendingSelections.Type.ADD_BOARD ? "クエストボードにする" : "クエストボードを解除する";
		c.getSource().sendSuccess(() -> Texts.info(action + "ブロックを右クリックしてください（60秒以内）"), false);
		return 1;
	}

	/** 壁のボードの表示を作り直す（表示が崩れた・消えたとき用） */
	private static int refreshBoards(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			manager().displays().forceRedraw();
			source.sendSuccess(() -> Texts.success("数秒以内にクエストボードの表示を作り直します"), false);
		});
	}

	/** /quest board demo <件数>: 表示確認用の見本クエストを作る */
	private static int addDemo(CommandContext<CommandSourceStack> c) {
		int count = IntegerArgumentType.getInteger(c, "count");
		return run(c, source -> {
			manager().addDemoQuests(count);
			source.sendSuccess(() -> Texts.success("見本のクエストを " + count + " 件作りました（掲示中 " + manager().quests().size()
					+ " 件）。消すときは /quest board demo clear"), false);
		});
	}

	/** /quest board demo clear: 見本クエストをすべて消す */
	private static int clearDemo(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			int removed = manager().clearDemoQuests();
			source.sendSuccess(() -> Texts.success("見本のクエストを " + removed + " 件消しました"), false);
		});
	}

	/** /quest guide: 使い方の説明ページの URL を、クリックで開ける形で表示する */
	private static int guide(CommandContext<CommandSourceStack> c) {
		return run(c, source -> QuestViews.sendGuide(message -> source.sendSuccess(() -> message, false),
				manager().guideUrl(), canManageBoards(source)));
	}

	/** /quest guide set <URL>: 説明ページの URL を設定する */
	private static int setGuide(CommandContext<CommandSourceStack> c) {
		String url = StringArgumentType.getString(c, "url").strip();
		return run(c, source -> {
			if (QuestViews.parseUrl(url) == null) {
				throw new QuestException("URL は http:// または https:// で始まる形で指定してください（空白は入れられません）");
			}
			manager().setGuideUrl(url);
			source.sendSuccess(() -> Texts.success("説明ページの URL を設定しました。/quest guide で表示されます"), false);
		});
	}

	/** /quest guide clear: 説明ページの URL を未設定に戻す */
	private static int clearGuide(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			manager().setGuideUrl(null);
			source.sendSuccess(() -> Texts.success("説明ページの URL を未設定に戻しました"), false);
		});
	}

	/** /quest extend <番号> <日数>: クエストの期限を延ばす（OP） */
	private static int extend(CommandContext<CommandSourceStack> c) {
		int days = IntegerArgumentType.getInteger(c, "days");
		return run(c, source -> {
			Quest quest = manager().getQuest(IntegerArgumentType.getInteger(c, "id"));
			if (QuestManager.daysLeft(quest) < 0) {
				throw new QuestException("クエスト #" + quest.id + " は発注日時の記録が無いため、期限がありません（自動取り消しされません）");
			}
			int left = manager().extendExpiry(quest, days);
			source.sendSuccess(() -> Texts.success("クエスト #" + quest.id + " の期限を " + days + " 日延ばしました（あと " + left
					+ " 日。発注から " + QuestManager.totalDays(quest) + " 日で自動取り消し）"), false);
		});
	}

	/** /quest resetid: 次のクエスト番号を振り直す */
	private static int resetId(CommandContext<CommandSourceStack> c) {
		return run(c, source -> {
			int next = manager().resetNextId();
			int open = manager().quests().size();
			source.sendSuccess(() -> Texts.success("次のクエスト番号を #" + next + " にしました"
					+ (open > 0 ? "（掲示中の " + open + " 件と重ならない番号です。#1 からにするには、先に掲示中のクエストを取り消してください）" : "")), false);
		});
	}

	private static int listBoards(CommandContext<CommandSourceStack> c) {
		CommandSourceStack source = c.getSource();
		List<BlockLocation> boards = manager().boards();
		source.sendSuccess(() -> Texts.info("クエストボード: " + boards.size() + " か所"), false);
		for (BlockLocation board : boards) {
			source.sendSuccess(() -> Component.literal("  " + board.describe()), false);
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// 共通処理
	// ------------------------------------------------------------------

	/** 次に右クリックしたチェストを納品先にする待ち状態へ */
	public static void startChestSelection(ServerPlayer player, Quest quest, String memo) {
		PendingSelections.set(player.getUUID(), PendingSelections.Type.BIND_CHEST, quest.id, memo, null);
		player.sendSystemMessage(Texts.info("納品用のチェストを右クリックしてください（60秒以内）。"
				+ "後から /quest chest " + quest.id + " [メモ] でも設定できます"));
		// チャットは見落としやすいので、画面中央下（ホットバーの上）にも出す
		player.sendOverlayMessage(Component.literal("クエスト #" + quest.id + " の納品用のチェストを右クリックしてください")
				.withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * コマンドの引数 id のクエストを取り出し、操作権限を確認する。
	 * @param allowOperator true なら OP も操作できる（取り消し用）
	 */
	private static Quest ownedQuest(CommandContext<CommandSourceStack> c, boolean allowOperator) throws QuestException {
		Quest quest = manager().getQuest(IntegerArgumentType.getInteger(c, "id"));
		CommandSourceStack source = c.getSource();
		ServerPlayer player = source.getPlayer(); // コンソールから実行した場合は null
		boolean isOwner = player != null && quest.ownerUuid.equals(player.getUUID().toString());
		boolean isOperator = allowOperator && Commands.LEVEL_GAMEMASTERS.check(source.permissions());
		if (!isOwner && !isOperator) {
			throw new QuestException("クエスト #" + quest.id + " の依頼主ではないため操作できません");
		}
		return quest;
	}

	/** ボードを管理できるか: OP、またはシングルプレイ（専用サーバでない）なら誰でも */
	private static boolean canManageBoards(CommandSourceStack source) {
		// コマンド一覧をクライアントへ送るときは source.getServer() が null になるため、起動環境で判定する
		boolean dedicated = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || !dedicated;
	}

	private static QuestManager manager() {
		return QuestBoardMod.manager();
	}

	/** QuestException を受け取ったら赤字で表示するための関数型 */
	@FunctionalInterface
	private interface Action {
		void run(CommandSourceStack source) throws QuestException;
	}

	/** 処理を実行し、失敗（QuestException）ならエラーメッセージを表示して 0 を返す */
	private static int run(CommandContext<CommandSourceStack> c, Action action) {
		CommandSourceStack source = c.getSource();
		if (manager() == null) {
			source.sendFailure(Component.literal("クエストボードはまだ準備中です"));
			return 0;
		}
		try {
			action.run(source);
			return 1;
		} catch (QuestException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}
}
