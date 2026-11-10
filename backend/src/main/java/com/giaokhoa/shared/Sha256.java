package com.giaokhoa.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 dùng chung: bản hex cho các giá trị băm lưu trữ, bản byte cho các phép suy diễn tiếp. */
public final class Sha256 {

    private Sha256() {}

    public static String hex(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
