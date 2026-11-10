package com.giaokhoa.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Advisory lock của PostgreSQL trong phạm vi transaction hiện hành: lock tự nhả khi transaction kết
 * thúc nên không cần đường mở khoá riêng, và hai instance của ứng dụng vẫn tôn trọng nhau. ID khoá
 * suy ra từ SHA-256 của namespace + key nên ổn định giữa các JVM; namespace tách các nhóm khoá
 * không liên quan khỏi đụng nhau.
 */
public final class AdvisoryLocks {

    private AdvisoryLocks() {}

    public static void lock(JdbcOperations jdbc, String namespace, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", _ -> {}, lockId(namespace, key));
    }

    private static long lockId(String namespace, String key) {
        byte[] digest = Sha256.digest((namespace + key).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest).getLong();
    }
}
