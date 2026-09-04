package com.pnas.common.error;

/**
 * 统一业务错误码。HTTP 状态码负责传输语义,此枚举给出机器可读的细分错误。
 */
public enum ErrorCode {

    // --- 通用 ---
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    INTERNAL_ERROR("internal_error"),

    // --- 账户/会话 ---
    INVALID_CREDENTIALS("invalid_credentials"),
    ACCOUNT_LOCKED("account_locked"),
    SESSION_EXPIRED("session_expired"),
    TOKEN_INVALID("token_invalid"),
    TOKEN_EXPIRED("token_expired"),
    USERNAME_TAKEN("username_taken"),
    WEAK_PASSWORD("weak_password"),

    // --- 文件/上传 ---
    NAME_CONFLICT("name_conflict"),
    PARENT_NOT_DIRECTORY("parent_not_directory"),
    QUOTA_EXCEEDED("quota_exceeded"),
    CHUNK_INVALID("chunk_invalid"),
    CHUNK_MISSING("chunk_missing"),
    UPLOAD_SESSION_INVALID("upload_session_invalid"),
    TRASH_RESTORE_CONFLICT("trash_restore_conflict");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
