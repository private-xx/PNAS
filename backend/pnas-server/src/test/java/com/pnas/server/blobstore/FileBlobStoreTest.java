package com.pnas.server.blobstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class FileBlobStoreTest {
    @TempDir Path dir;

    @Test
    void storesVerifiesAndDeduplicates() throws Exception {
        byte[] data = "hello-pnas-分块内容".getBytes(StandardCharsets.UTF_8);
        String sha = Sha256.hex(data);
        var store = new FileBlobStore(dir);
        store.store(new ByteArrayInputStream(data), data.length, sha);
        assertThat(store.exists(sha)).isTrue();
        store.store(new ByteArrayInputStream(data), data.length, sha); // 重复写不报错
        byte[] read = store.open(sha).readAllBytes();
        assertThat(read).isEqualTo(data);
    }
}
