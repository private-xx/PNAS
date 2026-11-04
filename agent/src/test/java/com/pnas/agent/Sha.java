package com.pnas.agent;

import com.pnas.agent.remote.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 测试辅助:模拟服务端首块/末块/清单哈希规则(块大小 4 MiB)。 */
public final class Sha {

    private static final int CHUNK = 4 * 1024 * 1024;

    private Sha() {
    }

    private static List<String> chunks(byte[] data) {
        List<String> hashes = new ArrayList<>();
        for (int off = 0; off < data.length; off += CHUNK) {
            int end = Math.min(data.length, off + CHUNK);
            hashes.add(Sha256.hex(Arrays.copyOfRange(data, off, end)));
        }
        return hashes;
    }

    public static String first(byte[] data) {
        var chunks = chunks(data);
        return chunks.isEmpty() ? null : chunks.get(0);
    }

    public static String last(byte[] data) {
        var chunks = chunks(data);
        return chunks.isEmpty() ? null : chunks.get(chunks.size() - 1);
    }

    public static String manifest(byte[] data) {
        return Sha256.hex(String.join("", chunks(data)).getBytes(StandardCharsets.UTF_8));
    }
}
