package com.pnas.server.common.error;

public record ApiError(String code, String message, Object detail) {}
