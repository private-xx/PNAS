package com.pnas.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** 测试辅助:极简 JSON 读写。 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> read(byte[] body) {
        try {
            return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
