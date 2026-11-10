package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.KeycloakSessionFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Khóa hành vi replay của SAVE_VERSION và RECEIVE_UPDATE qua route thật {@code POST
 * /api/workspaces/{id}/samples/actions}: retry cùng Idempotency-Key và cùng payload phải trả lại
 * đúng kết quả đầu tiên thay vì ghi thêm mục thư viện, khác payload cùng key thì 409. Mục thư viện
 * được đếm thẳng bằng SQL vì không có route nào liệt kê kho — chỉ khẳng định "không nhân bản", còn
 * hành vi và định danh trả về đều khẳng định qua thân đáp án của route.
 */
@SpringBootTest
class ResourceLibraryReplayIT extends KeycloakSessionFixture {

    @Test
    void saveVersionReplayReturnsFirstItemAndDifferentPayloadConflicts() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("replay-save-owner");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(ownerId);
            SystemResource system = seedSystemResource(ownerId);
            String key = "replay-save-" + UUID.randomUUID();
            String saveJson = saveJson(system.resourceId(), system.versionId1());
            UUID itemId;
            try (var owner = openSession(ownerLogin)) {
                JsonNode first = sampleActionWithKey(owner, center, saveJson, key, 201);
                assertEquals("SUCCEEDED", first.get("state").stringValue());
                assertEquals("LIBRARY_ITEM", first.get("targetType").stringValue());
                itemId = UUID.fromString(first.get("targetId").stringValue());
                JsonNode replayed = sampleActionWithKey(owner, center, saveJson, key, 201);
                assertEquals(
                        itemId.toString(),
                        replayed.get("targetId").stringValue(),
                        "Replay cùng key và cùng payload phải trả lại đúng mục thư viện đầu tiên");
                sampleActionWithKey(
                        owner,
                        center,
                        saveJson.replace(
                                system.versionId1().toString(), system.versionId2().toString()),
                        key,
                        409);
            }
            assertEquals(
                    1,
                    libraryItemCount(center, system.resourceId(), system.versionId1()),
                    "Replay cùng key không được ghi thêm mục thư viện thứ hai, payload khác cùng"
                            + " key phải bị 409 trước khi ghi");
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void receiveUpdateCreatesNewItemAndReplaysIt() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("replay-update-owner");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(ownerId);
            SystemResource system = seedSystemResource(ownerId);
            UUID firstItemId;
            UUID newItemId;
            String key = "replay-update-" + UUID.randomUUID();
            try (var owner = openSession(ownerLogin)) {
                JsonNode saved =
                        sampleActionWithKey(
                                owner,
                                center,
                                saveJson(system.resourceId(), system.versionId1()),
                                "replay-update-save-" + UUID.randomUUID(),
                                201);
                firstItemId = UUID.fromString(saved.get("targetId").stringValue());
                String updateJson =
                        "{\"action\":\"RECEIVE_UPDATE\",\"libraryItemId\":\""
                                + firstItemId
                                + "\",\"replacementVersionId\":\""
                                + system.versionId2()
                                + "\"}";
                JsonNode first = sampleActionWithKey(owner, center, updateJson, key, 201);
                assertEquals("SUCCEEDED", first.get("state").stringValue());
                newItemId = UUID.fromString(first.get("targetId").stringValue());
                assertNotEquals(
                        firstItemId,
                        newItemId,
                        "Nhận cập nhật sang version chưa lưu phải tạo mục thư viện mới");
                assertEquals(
                        system.versionId2(),
                        jdbc.queryForObject(
                                "SELECT version_id FROM library_item WHERE id = ?",
                                UUID.class,
                                newItemId),
                        "Mục mới phải trỏ version thay thế");
                assertEquals(
                        jdbc.queryForObject(
                                "SELECT custody FROM library_item WHERE id = ?",
                                String.class,
                                firstItemId),
                        jdbc.queryForObject(
                                "SELECT custody FROM library_item WHERE id = ?",
                                String.class,
                                newItemId),
                        "Mục mới giữ custody của mục gốc");
                JsonNode replayed = sampleActionWithKey(owner, center, updateJson, key, 201);
                assertEquals(
                        newItemId.toString(),
                        replayed.get("targetId").stringValue(),
                        "Replay nhận cập nhật cùng key phải trả lại đúng mục mới đầu tiên");
            }
            assertEquals(
                    1,
                    libraryItemCount(center, system.resourceId(), system.versionId2()),
                    "Replay nhận cập nhật không được ghi thêm mục thứ hai cho version thay thế");
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void receiveUpdateReusesExistingItemForReplacementVersion() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("replay-reuse-owner");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(ownerId);
            SystemResource system = seedSystemResource(ownerId);
            UUID firstItemId;
            UUID secondItemId;
            String key = "replay-reuse-" + UUID.randomUUID();
            try (var owner = openSession(ownerLogin)) {
                JsonNode savedFirst =
                        sampleActionWithKey(
                                owner,
                                center,
                                saveJson(system.resourceId(), system.versionId1()),
                                "replay-reuse-save-first-" + UUID.randomUUID(),
                                201);
                firstItemId = UUID.fromString(savedFirst.get("targetId").stringValue());
                JsonNode savedSecond =
                        sampleActionWithKey(
                                owner,
                                center,
                                saveJson(system.resourceId(), system.versionId2()),
                                "replay-reuse-save-second-" + UUID.randomUUID(),
                                201);
                secondItemId = UUID.fromString(savedSecond.get("targetId").stringValue());
                String updateJson =
                        "{\"action\":\"RECEIVE_UPDATE\",\"libraryItemId\":\""
                                + firstItemId
                                + "\",\"replacementVersionId\":\""
                                + system.versionId2()
                                + "\"}";
                JsonNode first = sampleActionWithKey(owner, center, updateJson, key, 201);
                assertEquals(
                        secondItemId.toString(),
                        first.get("targetId").stringValue(),
                        "Version thay thế đã có mục thư viện thì nhận cập nhật phải tái dùng đúng"
                                + " mục đó thay vì tạo mục mới");
                JsonNode replayed = sampleActionWithKey(owner, center, updateJson, key, 201);
                assertEquals(
                        secondItemId.toString(),
                        replayed.get("targetId").stringValue(),
                        "Replay nhận cập nhật cùng key phải trả lại đúng mục đã tái dùng");
            }
            assertEquals(
                    1,
                    libraryItemCount(center, system.resourceId(), system.versionId2()),
                    "Nhận cập nhật tái dùng không được ghi thêm mục thứ hai cho version thay thế");
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    // ---------- HTTP ----------

    private String saveJson(UUID resourceId, UUID versionId) {
        return "{\"action\":\"SAVE_VERSION\",\"resourceId\":\""
                + resourceId
                + "\",\"versionId\":\""
                + versionId
                + "\"}";
    }

    private JsonNode sampleActionWithKey(
            RealSession session,
            UUID workspaceId,
            String json,
            String idempotencyKey,
            int expectedStatus)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                post("/api/workspaces/" + workspaceId + "/samples/actions")
                                        .cookie(session.cookie())
                                        .with(csrf())
                                        .header("Idempotency-Key", idempotencyKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andReturn()
                        .getResponse();
        assertEquals(
                expectedStatus,
                response.getStatus(),
                "Sample action tại workspace "
                        + workspaceId
                        + ", body: "
                        + response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    private int libraryItemCount(UUID workspaceId, UUID resourceId, UUID versionId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM library_item WHERE workspace_id = ?"
                                + " AND resource_id = ? AND version_id = ?",
                        Integer.class,
                        workspaceId,
                        resourceId,
                        versionId),
                "count");
    }

    // ---------- Dữ kiện ----------

    /** Resource hệ thống với hai version đã LOCKED, để nhánh hệ thống cấp library.save. */
    private record SystemResource(UUID resourceId, UUID versionId1, UUID versionId2) {}

    private SystemResource seedSystemResource(UUID createdBy) {
        UUID systemWorkspace = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID versionId1 = UUID.randomUUID();
        UUID versionId2 = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,state) VALUES (?,'SYSTEM','ACTIVE')",
                    systemWorkspace);
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    systemWorkspace,
                    createdBy,
                    UUID.randomUUID());
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state,locked_at)"
                            + " VALUES (?,?,1,'Bản hệ thống 1','Nội dung 1','Đáp án 1','LOCKED',?)",
                    versionId1,
                    resourceId,
                    Timestamp.from(Instant.now()));
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state,locked_at)"
                            + " VALUES (?,?,2,'Bản hệ thống 2','Nội dung 2','Đáp án 2','LOCKED',?)",
                    versionId2,
                    resourceId,
                    Timestamp.from(Instant.now()));
            return null;
        });
        return new SystemResource(resourceId, versionId1, versionId2);
    }

    private UUID seedAccount(
            String keycloakSub, String origin, @Nullable String email, boolean emailVerified) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    keycloakSub,
                    "replay-user-" + actor,
                    email,
                    emailVerified,
                    origin);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state)"
                            + " VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "center-org-" + center,
                    "Center test",
                    ownerUserId);
            // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER của chủ sở hữu.
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    ownerMembership,
                    workspace,
                    ownerUserId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')",
                    ownerMembership);
            return null;
        });
        return workspace;
    }
}
