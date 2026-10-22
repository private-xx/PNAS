package com.pnas.server.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** 流工具:把多个分块流拼接成一个逻辑流,并支持"从指定块起 + 内部偏移 + 限长"。 */
public final class Streams {

    private Streams() {}

    /** 惰性打开一个分块流;允许抛出受检 IOException,由读取方按原样向上传播。 */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * 惰性顺序拼接:仅在需要读到某一块时才调用对应 {@link Opener} 打开它,读完立即关闭。
     *
     * <p>与 {@link #concat(List)} 的区别:后者要求调用方先打开全部分块,大文件会同时占用
     * N 个文件描述符(2GB/4MiB ≈ 512 FD),在 Docker/macOS 的低 FD 上限下可能 EMFILE。</p>
     */
    public static InputStream lazyConcat(List<Opener> parts) {
        Objects.requireNonNull(parts, "parts");
        Iterator<Opener> it = parts.iterator();
        return new InputStream() {
            private InputStream current;
            private boolean exhausted;
            private boolean closed;

            /** 确保 current 指向一个未读尽的流;没有更多分块时返回 false。 */
            private boolean ensureOpen() throws IOException {
                if (current != null) {
                    return true;
                }
                if (exhausted) {
                    return false;
                }
                while (it.hasNext()) {
                    InputStream next = it.next().open();
                    if (next != null) {
                        current = next;
                        return true;
                    }
                }
                exhausted = true;
                return false;
            }

            private void closeCurrent() throws IOException {
                InputStream c = current;
                current = null;
                if (c != null) {
                    c.close();
                }
            }

            private void ensureNotClosed() throws IOException {
                if (closed) {
                    throw new IOException("流已关闭");
                }
            }

            @Override
            public int read() throws IOException {
                ensureNotClosed();
                while (true) {
                    if (!ensureOpen()) {
                        return -1;
                    }
                    int b = current.read();
                    if (b >= 0) {
                        return b;
                    }
                    closeCurrent();
                }
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                ensureNotClosed();
                Objects.checkFromIndexSize(off, len, buf.length);
                if (len == 0) {
                    return 0;
                }
                while (true) {
                    if (!ensureOpen()) {
                        return -1;
                    }
                    int n = current.read(buf, off, len);
                    if (n >= 0) {
                        return n;
                    }
                    closeCurrent();
                }
            }

            @Override
            public long skip(long n) throws IOException {
                ensureNotClosed();
                if (n <= 0) {
                    return 0;
                }
                long remaining = n;
                while (remaining > 0) {
                    if (!ensureOpen()) {
                        break;
                    }
                    long skipped = current.skip(remaining);
                    if (skipped > 0) {
                        remaining -= skipped;
                    } else {
                        closeCurrent();
                    }
                }
                return n - remaining;
            }

            @Override
            public int available() throws IOException {
                return current == null ? 0 : current.available();
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                closeCurrent();
            }
        };
    }

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
