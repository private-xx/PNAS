package com.pnas.server.blobstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface BlobStore {
    boolean exists(String sha256);
    void store(InputStream in, long size, String sha256) throws IOException;
    InputStream open(String sha256) throws IOException;
    Path blobPath(String sha256);
}
