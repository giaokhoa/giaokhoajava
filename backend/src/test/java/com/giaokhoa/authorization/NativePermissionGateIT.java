package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Acceptance test PDP native: chỉ chạy bằng infra/keycloak/verify-native.sh. */
class NativePermissionGateIT {
    private final NativeGateClient gate = new NativeGateClient();

    @Test
    void groupMembershipGrantsOnlyTheExactResourceAndScope() throws IOException {
        String token = gate.token("native-gate-assigned");
        JsonNode claims =
                new JsonMapper().readTree(Base64.getUrlDecoder().decode(token.split("[.]")[1]));
        assertFalse(
                claims.has("groups"), "Native group policy must not rely on access-token groups");

        assertTrue(gate.decide(token, gate.required("NATIVE_GATE_RESOURCE_A_ID"), "read"));
        assertFalse(gate.decide(token, gate.required("NATIVE_GATE_RESOURCE_A_ID"), "update"));
        assertFalse(gate.decide(token, gate.required("NATIVE_GATE_RESOURCE_B_ID"), "read"));
    }

    @Test
    void forgedGroupClaimAndRealmRoleCannotReplaceNativeMembership() throws IOException {
        String token = gate.token("native-gate-claim-only");
        String encodedClaims = token.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(encodedClaims);
        JsonNode claims = new JsonMapper().readTree(decoded);

        // ID giữ cố định: phải khớp byte-exact với group đã seed ở
        // infra/keycloak/fixtures/native-gate.json, vì claim groups trong JWT lấy từ đó.
        assertEquals(
                "/gk/workspaces/71000000-0000-4000-8000-000000000015"
                        + "/course-subjects/71000000-0000-4000-8000-0000000000a1/teachers",
                claims.path("groups").stringValue());
        assertTrue(
                new String(decoded, StandardCharsets.UTF_8).contains("native-gate-teacher-role"),
                "The denied actor must actually carry the decoy realm role");
        assertFalse(gate.decide(token, gate.required("NATIVE_GATE_RESOURCE_A_ID"), "read"));
        assertTrue(
                gate.decide(token, gate.required("NATIVE_GATE_RESOURCE_B_ID"), "read"),
                "The same actor must retain its independent native B permission");
    }

    @Test
    void batchWithOneGrantMustNotBecomeAllRequiredPairs() {
        String actorToken = gate.token("native-gate-assigned");
        String resourceA = gate.required("NATIVE_GATE_RESOURCE_A_ID");
        String resourceB = gate.required("NATIVE_GATE_RESOURCE_B_ID");

        List<Map<String, Object>> granted =
                gate.permissions(actorToken, resourceA + "#read", resourceB + "#read");
        assertEquals(1, granted.size(), "Batch may return only the granted pair");
        assertEquals(resourceA, granted.getFirst().get("rsid"));
        assertEquals(List.of("read"), granted.getFirst().get("scopes"));

        // PEP của permission enforcement layer phải đánh giá mọi cặp bắt buộc, không được diễn giải
        // một
        // batch dở dang thành ALL.
        assertFalse(
                gate.decide(actorToken, resourceA, "read")
                        && gate.decide(actorToken, resourceB, "read"));
    }
}
