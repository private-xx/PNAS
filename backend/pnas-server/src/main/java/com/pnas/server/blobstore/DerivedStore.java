package com.pnas.server.blobstore;

import com.pnas.server.common.PnasProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 派生缓存目录(可再生资源):derived/thumbs/<sha 前 2 位>/<versionSha>-<size>.jpg。 */
@Component
public class DerivedStore {

    private final Path thumbsRoot;

    public DerivedStore(PnasProperties props) throws IOException {
        this.thumbsRoot = props.dataDir().resolve("derived").resolve("thumbs");
        Files.createDirectories(thumbsRoot);
    }

    public Path thumbPath(String versionSha, int size) {
        return thumbsRoot.resolve(versionSha.substring(0, 2)).resolve(versionSha + "-" + size + ".jpg");
    }

    public boolean exists(String versionSha, int size) {
        return Files.exists(thumbPath(versionSha, size));
    }

    public InputStream open(String versionSha, int size) throws IOException {
        return Files.newInputStream(thumbPath(versionSha, size));
    }

    public void writeAtomic(String versionSha, int size, byte[] jpeg) throws IOException {
        Path target = thumbPath(versionSha, size);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(thumbsRoot, "thumb-", ".tmp");
        try {
            Files.write(tmp, jpeg);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
