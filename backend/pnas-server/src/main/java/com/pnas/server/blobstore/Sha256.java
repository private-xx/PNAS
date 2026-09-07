package com.pnas.server.blobstore;

import java.security.MessageDigest;
import java.util.HexFormat;

public final class Sha256 {
    private Sha256() {}
    public static String hex(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
