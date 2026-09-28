package com.pnas.server.common.error;

import com.pnas.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final HttpStatus status;

    public BusinessException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public ErrorCode code() { return code; }
    public HttpStatus status() { return status; }
}
