package com.questboard.util;

/**
 * 「依頼主ではない」「クエストが無い」など、利用者に伝えるべき失敗。
 * コマンド側で受け取り、赤字のエラーメッセージとして表示する。
 */
public class QuestException extends Exception {
	public QuestException(String message) {
		super(message);
	}
}
