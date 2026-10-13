package com.questboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.questboard.model.QuestData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * クエストデータを JSON ファイルに保存・読み込みするクラス。
 * 保存先: ワールドフォルダ/questboard.json（例: サーバ/world/questboard.json）
 * 人が読める形式なので、トラブル時に中身を確認・手修正できる。
 */
public final class QuestStorage {
	private static final Logger LOGGER = LoggerFactory.getLogger("questboard");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;

	public QuestStorage(Path file) {
		this.file = file;
	}

	/** ファイルを読み込む。無ければ空データ、壊れていれば退避してから空データを返す */
	public QuestData load() {
		if (!Files.exists(file)) {
			return new QuestData();
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			QuestData data = GSON.fromJson(json, QuestData.class);
			if (data == null) {
				data = new QuestData();
			}
			data.fillDefaults();
			return data;
		} catch (IOException | JsonParseException e) {
			// 壊れたファイルを上書きして消さないよう、別名で残しておく
			Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
			LOGGER.error("questboard.json を読み込めませんでした。{} に退避して空の状態で開始します", backup, e);
			try {
				Files.move(file, backup);
			} catch (IOException moveError) {
				LOGGER.error("壊れたファイルの退避に失敗しました", moveError);
			}
			return new QuestData();
		}
	}

	/**
	 * ファイルに保存する。
	 * 一時ファイルに書いてから置き換えるので、書き込み途中で落ちても元ファイルは壊れない。
	 */
	public void save(QuestData data) {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("questboard.json の保存に失敗しました", e);
		}
	}
}
