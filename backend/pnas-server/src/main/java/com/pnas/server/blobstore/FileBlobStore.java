package com.pnas.server.blobstore;

import com.pnas.server.common.PnasProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class FileBlobStore implements BlobStore {
    private final Path root;

    public FileBlobStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root.resolve("blobs"));
        Files.createDirectories(root.resolve("tmp"));
    }

    @Autowired
    public FileBlobStore(PnasProperties props) throws IOException {
        this(props.dataDir());
    }

    @Override public Path blobPath(String sha) {
        return root.resolve("blobs").resolve(sha.substring(0, 2)).resolve(sha);
    }

    @Override public boolean exists(String sha) { return Files.exists(blobPath(sha)); }

    @Override public void store(InputStream in, long size, String sha) throws IOException {
        Path target = blobPath(sha);
        if (Files.exists(target)) return;
        Path tmp = root.resolve("tmp").resolve(UUID.randomUUID() + ".tmp");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) { throw new IllegalStateException(e); }
        long total = 0;
        try (in; var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) { out.write(buf, 0, n); md.update(buf, 0, n); total += n; }
            }
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equals(sha)) {
            Files.deleteIfExists(tmp);
            throw new IOException("chunk sha256 mismatch: expected " + sha + " got " + actual);
        }
        Files.createDirectories(target.getParent());
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override public InputStream open(String sha) throws IOException {
        return Files.newInputStream(blobPath(sha));
    }
}
