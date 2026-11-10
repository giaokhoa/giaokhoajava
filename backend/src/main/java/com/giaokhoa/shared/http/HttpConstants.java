package com.giaokhoa.shared.http;

/**
 * Tên header và tiền tố đường dẫn HTTP dùng chung cho mọi controller: header idempotency của hợp
 * đồng HTTP và tiền tố route workspace mà các controller tự dựng lại khi tính payload hash.
 */
public final class HttpConstants {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String WORKSPACE_API_PREFIX = "/api/workspaces/";

    private HttpConstants() {}
}
