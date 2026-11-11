package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.giaokhoa.support.HttpTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Precondition If-Match của hai route đổi tên giảng dạy: PATCH lớp/khóa là cập nhật đối tượng hiện
 * có nên theo hợp đồng HTTP phải mang If-Match chứa row version — thiếu trả 428, version cũ trả 409
 * và không ghi gì, version đúng mới đổi tên và tăng version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class TeachingRenamePreconditionE2EIT extends TeachingFixture {

    // Tầng e2e-backend : mọi request vào ứng dụng đi qua HTTP thật — chuyển ba điểm nối
    // vận chuyển của fixture sang biến thể HTTP để toàn bộ helper tổ hợp chảy qua server thật.
    @Override
    protected RealSession openSession(KeycloakLogin login) throws Exception {
        return openSessionHttp(login);
    }

    @Override
    protected UUID postMutation(RealSession session, String uri, String json, String method)
            throws Exception {
        return postMutationHttp(session, uri, json, method);
    }

    @Override
    protected JsonNode getJson(RealSession session, String uri, int expectedStatus)
            throws Exception {
        return getJsonHttp(session, uri, expectedStatus);
    }

    @Test
    void renameClassRequiresCurrentIfMatch() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp precondition");
            String uri = "/api/workspaces/" + fixture.workspaceId() + "/classes/" + classId;

            HttpTestClient.Response missing = patchRename(session, uri, "Tên mới", null);
            assertEquals(428, missing.status(), "Thiếu If-Match phải trả 428");
            assertEquals(
                    "PRECONDITION_REQUIRED",
                    JSON.readTree(missing.body()).get("code").stringValue());

            HttpTestClient.Response stale = patchRename(session, uri, "Tên mới", "\"99\"");
            assertEquals(409, stale.status(), "Version không khớp phải trả 409");
            assertEquals("VERSION_CONFLICT", JSON.readTree(stale.body()).get("code").stringValue());

            JsonNode before = getJson(session, uri, 200);
            assertEquals(
                    "Lớp precondition",
                    before.get("name").stringValue(),
                    "Hai lần bị từ chối không được ghi gì");
            assertEquals(0, before.get("version").longValue());

            HttpTestClient.Response renamed = patchRename(session, uri, "Tên mới", "\"0\"");
            assertEquals(202, renamed.status(), "Version đúng phải được chấp nhận");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.body()).get("operationId").stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Tên mới", after.get("name").stringValue());
            assertEquals(1, after.get("version").longValue());

            HttpTestClient.Response outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409, outdated.status(), "Version cũ sau lần đổi tên thành công phải trả 409");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void renameCourseRequiresCurrentIfMatch() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp chứa khóa");
            List<UUID> subjects = subjectIds(session, 1);
            UUID courseId =
                    createCourse(
                            session, fixture.workspaceId(), classId, "Khóa precondition", subjects);
            String uri = "/api/workspaces/" + fixture.workspaceId() + "/courses/" + courseId;

            HttpTestClient.Response missing = patchRename(session, uri, "Tên mới", null);
            assertEquals(428, missing.status(), "Thiếu If-Match phải trả 428");
            assertEquals(
                    "PRECONDITION_REQUIRED",
                    JSON.readTree(missing.body()).get("code").stringValue());

            HttpTestClient.Response stale = patchRename(session, uri, "Tên mới", "\"99\"");
            assertEquals(409, stale.status(), "Version không khớp phải trả 409");
            assertEquals("VERSION_CONFLICT", JSON.readTree(stale.body()).get("code").stringValue());

            JsonNode before = getJson(session, uri, 200);
            assertEquals(
                    "Khóa precondition",
                    before.get("name").stringValue(),
                    "Hai lần bị từ chối không được ghi gì");
            assertEquals(0, before.get("version").longValue());

            HttpTestClient.Response renamed = patchRename(session, uri, "Tên mới", "\"0\"");
            assertEquals(202, renamed.status(), "Version đúng phải được chấp nhận");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.body()).get("operationId").stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Tên mới", after.get("name").stringValue());
            assertEquals(1, after.get("version").longValue());

            HttpTestClient.Response outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409, outdated.status(), "Version cũ sau lần đổi tên thành công phải trả 409");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    @DisplayName("Đổi tên lớp trùng tên hiện tại vẫn thành công và version tăng đúng một ở DB")
    void renameClassToSameNameAdvancesVersion() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp giữ tên");
            String uri = "/api/workspaces/" + fixture.workspaceId() + "/classes/" + classId;

            HttpTestClient.Response renamed = patchRename(session, uri, "Lớp giữ tên", "\"0\"");
            assertEquals(
                    202, renamed.status(), "Đổi tên trùng tên hiện tại vẫn là đổi tên thành công");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.body()).get("operationId").stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Lớp giữ tên", after.get("name").stringValue());
            assertEquals(
                    1,
                    after.get("version").longValue(),
                    "Version phải tăng ở DB dù tên không đổi, như SQL cũ tăng vô điều kiện");

            HttpTestClient.Response outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409, outdated.status(), "Version cũ phải bị từ chối sau lần đổi tên trùng tên");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    @DisplayName("Đổi tên khóa trùng tên hiện tại vẫn thành công và version tăng đúng một ở DB")
    void renameCourseToSameNameAdvancesVersion() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp chứa khóa");
            List<UUID> subjects = subjectIds(session, 1);
            UUID courseId =
                    createCourse(session, fixture.workspaceId(), classId, "Khóa giữ tên", subjects);
            String uri = "/api/workspaces/" + fixture.workspaceId() + "/courses/" + courseId;

            HttpTestClient.Response renamed = patchRename(session, uri, "Khóa giữ tên", "\"0\"");
            assertEquals(
                    202, renamed.status(), "Đổi tên trùng tên hiện tại vẫn là đổi tên thành công");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.body()).get("operationId").stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Khóa giữ tên", after.get("name").stringValue());
            assertEquals(
                    1,
                    after.get("version").longValue(),
                    "Version phải tăng ở DB dù tên không đổi, như SQL cũ tăng vô điều kiện");

            HttpTestClient.Response outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409, outdated.status(), "Version cũ phải bị từ chối sau lần đổi tên trùng tên");
        } finally {
            deleteFixture(fixture);
        }
    }

    private HttpTestClient.Response patchRename(
            RealSession session, String uri, String name, @Nullable String ifMatch) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Idempotency-Key", "teaching-rename-" + UUID.randomUUID());
        if (ifMatch != null) {
            headers.put("If-Match", ifMatch);
        }
        return realHttp()
                .exchange(
                        HttpMethod.PATCH,
                        uri,
                        session.cookie(),
                        true,
                        "{\"name\":\"" + name + "\"}",
                        headers);
    }
}
