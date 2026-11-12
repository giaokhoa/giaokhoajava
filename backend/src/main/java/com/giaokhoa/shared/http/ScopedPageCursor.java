package com.giaokhoa.shared.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Cursor ký HMAC theo actor, workspace, danh sách/filter và limit. Client không thể lấy cursor từ
 * actor/workspace A dùng lại trong truy vấn B, kể cả khi anchor UUID vẫn còn trong DB. Một instance
 * giữ khóa suốt vòng đời service; sau khi restart, cursor cũ trả 400 (caller tải lại trang đầu).
 * Bản hiện hành một backend instance; khi mở rộng HA cần secret chia sẻ an toàn.
 */
public final class ScopedPageCursor {
    private static final int MAX_LENGTH = 256;
    private final byte[] key = new byte[32];

    public ScopedPageCursor() {
        new SecureRandom().nextBytes(key);
    }

    public String encode(UUID anchor, String binding) {
        return anchor
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(anchor, binding));
    }

    public UUID decode(String cursor, String binding) {
        if (cursor.length() > MAX_LENGTH) throw invalid();
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 2) throw invalid();
        try {
            UUID anchor = UUID.fromString(parts[0]);
            if (!anchor.toString().equals(parts[0])) throw invalid();
            byte[] expected = sign(anchor, binding);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expected, supplied)) throw invalid();
            return anchor;
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private byte[] sign(UUID anchor, String binding) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((binding + "|" + anchor).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign page cursor", ex);
        }
    }

    private static HttpProblemException invalid() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.INVALID_CURSOR,
                "Cursor danh sách không hợp lệ.");
    }
}
