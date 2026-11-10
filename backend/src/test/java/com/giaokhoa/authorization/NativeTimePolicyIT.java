package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Cổng time policy Keycloak 26.8.1 native: [start,end) UTC chính xác qua admin policy evaluator
 * thật, cộng các quyết định UMA thời gian thực với user access token không đổi.
 */
class NativeTimePolicyIT {
    private static final DateTimeFormatter KEYCLOAK_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NativeGateClient gate = new NativeGateClient();
    private final RestClient http = RestClient.create();

    @DisplayName(
            "Native time policy bao gồm thời điểm bắt đầu (inclusive) và loại trừ đúng thời điểm kết thúc (exclusive)")
    @Test
    void timePolicyStartInclusiveEndExclusive() {
        String actor = gate.token("native-gate-assigned");
        String resource = gate.required("NATIVE_GATE_ACTIVITY_ID");
        String startId = gate.required("NATIVE_GATE_START_POLICY_ID");
        String endId = gate.required("NATIVE_GATE_END_POLICY_ID");
        String provisioner = provisionerToken();

        // Trong fixture mặc định cả hai biên native đều ở tương lai.
        assertFalse(gate.decide(actor, resource, "attempt.open"));

        // Policy evaluator chỉ-dành-cho-admin của Keycloak thực thi policy graph *thật* với
        // một evaluation context chính xác. Không bao giờ gửi kc.time.date_time trong UMA request:
        // quyết định production phải luôn dùng đồng hồ server của Keycloak.
        Instant start = Instant.parse("2033-01-15T10:00:00Z");
        Instant end = start.plusSeconds(60);
        updateNotBefore(startId, "POSITIVE", start, provisioner);
        updateNotBefore(endId, "NEGATIVE", end, provisioner);
        assertEquals("DENY", evaluateAt(start.minusSeconds(1), provisioner));
        assertEquals("PERMIT", evaluateAt(start, provisioner));
        assertEquals("PERMIT", evaluateAt(end.minusSeconds(1), provisioner));
        assertEquals("DENY", evaluateAt(end, provisioner));
        assertEquals("DENY", evaluateAt(end.plusSeconds(1), provisioner));

        // UMA thật: xác nhận cổng lật mà không cần logout, refresh hay tiến trình cron.
        // Cả hai mốc so sánh được cố ý đặt xa hiện tại; độ trễ CI không thể bỏ qua một
        // cửa sổ thời gian thực hẹp.
        updateNotBefore(startId, "POSITIVE", Instant.now().minusSeconds(180), provisioner);
        updateNotBefore(endId, "NEGATIVE", Instant.now().plusSeconds(180), provisioner);
        assertTrue(gate.decide(actor, resource, "attempt.open"));
        updateNotBefore(endId, "NEGATIVE", Instant.now().minusSeconds(180), provisioner);
        assertFalse(gate.decide(actor, resource, "attempt.open"));
    }

    private String evaluateAt(Instant instant, String provisioner) {
        String endpoint =
                gate.required("NATIVE_GATE_KEYCLOAK_BASE_URL")
                        + "/admin/realms/giaokhoa/clients/"
                        + gate.required("NATIVE_GATE_API_CLIENT_ID")
                        + "/authz/resource-server/policy/evaluate";
        Map<String, Object> request =
                Map.of(
                        "userId", gate.required("NATIVE_GATE_ASSIGNED_USER_ID"),
                        "clientId", gate.required("NATIVE_GATE_API_CLIENT_ID"),
                        "resources",
                                List.of(
                                        Map.of(
                                                "_id", gate.required("NATIVE_GATE_ACTIVITY_ID"),
                                                "scopes", List.of(Map.of("name", "attempt.open")))),
                        "context",
                                Map.of(
                                        "attributes",
                                        Map.of("kc.time.date_time", KEYCLOAK_UTC.format(instant))));
        Map<String, Object> response =
                http.post()
                        .uri(endpoint)
                        .headers(headers -> headers.setBearerAuth(provisioner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return String.valueOf(
                Objects.requireNonNull(response, "No native evaluation").get("status"));
    }

    private String provisionerToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "giaokhoa-provisioner");
        form.add("client_secret", gate.required("KEYCLOAK_PROVISIONER_CLIENT_SECRET"));
        Map<String, Object> result =
                http.post()
                        .uri(
                                gate.required("NATIVE_GATE_KEYCLOAK_BASE_URL")
                                        + "/realms/giaokhoa/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return Objects.requireNonNull(
                (String)
                        Objects.requireNonNull(result, "Missing provisioner token")
                                .get("access_token"),
                "access_token");
    }

    private void updateNotBefore(String id, String logic, Instant boundary, String token) {
        String endpoint =
                gate.required("NATIVE_GATE_KEYCLOAK_BASE_URL")
                        + "/admin/realms/giaokhoa/clients/"
                        + gate.required("NATIVE_GATE_API_CLIENT_ID")
                        + "/authz/resource-server/policy/time/"
                        + id;
        Map<String, Object> current =
                http.get()
                        .uri(endpoint)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        Map<String, Object> update = new HashMap<>(Objects.requireNonNull(current));
        assertNull(
                update.get("notOnOrAfter"),
                "Keycloak's inclusive noa must not replace negative nbf=end");
        update.put("notBefore", KEYCLOAK_UTC.format(boundary));
        update.put("logic", logic);
        http.put()
                .uri(endpoint)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve()
                .toBodilessEntity();
    }
}
