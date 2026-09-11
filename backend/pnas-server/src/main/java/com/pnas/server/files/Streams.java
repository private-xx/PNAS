package com.pnas.server.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/** 流工具:把多个分块流拼接成一个逻辑流,并支持"从指定块起 + 内部偏移 + 限长"。 */
public final class Streams {

    private Streams() {}

    /** 顺序拼接若干输入流(读完一个自动切下一个)。 */
    public static InputStream concat(List<InputStream> parts) {
        Iterator<InputStream> it = parts.iterator();
        return new InputStream() {
            private InputStream current = it.hasNext() ? it.next() : InputStream.nullInputStream();

            @Override
            public int read() throws IOException {
                int b;
                while ((b = current.read()) < 0) {
                    if (!it.hasNext()) {
                        return -1;
                    }
                    current = it.next();
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int n;
                while ((n = current.read(buf, off, len)) < 0) {
                    if (!it.hasNext()) {
                        return -1;
                    }
                    current = it.next();
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long remaining = n;
                while (remaining > 0) {
                    long skipped = current.skip(remaining);
                    if (skipped > 0) {
                        remaining -= skipped;
                        continue;
                    }
                    if (!it.hasNext()) {
                        break;
                    }
                    current = it.next();
                }
                return n - remaining;
            }

            @Override
            public void close() throws IOException {
                for (InputStream s : parts) {
                    s.close();
                }
            }
        };
    }

    /** 只允许读取最多 limit 字节的包装流(Range 下载用)。 */
    public static InputStream limit(InputStream in, long limit) {
        return new InputStream() {
            private long remaining = limit;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b >= 0) {
                    remaining--;
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = in.read(buf, off, (int) Math.min(len, remaining));
                if (n > 0) {
                    remaining -= n;
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }
}
