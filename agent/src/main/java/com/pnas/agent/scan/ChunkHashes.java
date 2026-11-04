package com.pnas.agent.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 计算文件首块与末块的 SHA-256;空文件为 {null, null}。 */
public final class ChunkHashes {

    private ChunkHashes() {
    }

    public static String[] firstAndLast(Path file, int chunkSize) throws IOException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        long size = Files.size(file);
        if (size == 0) {
            return new String[]{null, null};
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int firstLen = (int) Math.min(chunkSize, size);
            String first = sha(ch, file, 0, firstLen);
            if (size <= chunkSize) {
                return new String[]{first, first};
            }
            long rem = size % chunkSize;
            long lastStart = rem == 0 ? size - chunkSize : size - rem;
            return new String[]{first, sha(ch, file, lastStart, (int) (size - lastStart))};
        }
    }

    private static String sha(FileChannel ch, Path file, long offset, int length) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer buf = ByteBuffer.allocate(Math.min(length, 1 << 16));
        long pos = offset;
        int remaining = length;
        while (remaining > 0) {
            buf.clear();
            buf.limit(Math.min(buf.capacity(), remaining));
            int read = ch.read(buf, pos);
            if (read <= 0) {
                throw new IOException("unexpected EOF while hashing " + file);
            }
            buf.flip();
            md.update(buf);
            pos += read;
            remaining -= read;
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
