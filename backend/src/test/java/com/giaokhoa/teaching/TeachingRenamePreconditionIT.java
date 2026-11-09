package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Precondition If-Match của hai route đổi tên giảng dạy: PATCH lớp/khóa là cập nhật đối tượng hiện
 * có nên theo hợp đồng HTTP phải mang If-Match chứa row version — thiếu trả 428, version cũ trả 409
 * và không ghi gì, version đúng mới đổi tên và tăng version.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class TeachingRenamePreconditionIT extends TeachingFixture {

    @Test
    void renameClassRequiresCurrentIfMatch() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp precondition");
            String uri = "/api/workspaces/" + fixture.workspaceId() + "/classes/" + classId;

            MockHttpServletResponse missing = patchRename(session, uri, "Tên mới", null);
            assertEquals(428, missing.getStatus(), "Thiếu If-Match phải trả 428");
            assertEquals(
                    "PRECONDITION_REQUIRED",
                    JSON.readTree(missing.getContentAsString()).get("code").stringValue());

            MockHttpServletResponse stale = patchRename(session, uri, "Tên mới", "\"99\"");
            assertEquals(409, stale.getStatus(), "Version không khớp phải trả 409");
            assertEquals(
                    "VERSION_CONFLICT",
                    JSON.readTree(stale.getContentAsString()).get("code").stringValue());

            JsonNode before = getJson(session, uri, 200);
            assertEquals(
                    "Lớp precondition",
                    before.get("name").stringValue(),
                    "Hai lần bị từ chối không được ghi gì");
            assertEquals(0, before.get("version").longValue());

            MockHttpServletResponse renamed = patchRename(session, uri, "Tên mới", "\"0\"");
            assertEquals(202, renamed.getStatus(), "Version đúng phải được chấp nhận");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.getContentAsString())
                                    .get("operationId")
                                    .stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Tên mới", after.get("name").stringValue());
            assertEquals(1, after.get("version").longValue());

            MockHttpServletResponse outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409,
                    outdated.getStatus(),
                    "Version cũ sau lần đổi tên thành công phải trả 409");
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

            MockHttpServletResponse missing = patchRename(session, uri, "Tên mới", null);
            assertEquals(428, missing.getStatus(), "Thiếu If-Match phải trả 428");
            assertEquals(
                    "PRECONDITION_REQUIRED",
                    JSON.readTree(missing.getContentAsString()).get("code").stringValue());

            MockHttpServletResponse stale = patchRename(session, uri, "Tên mới", "\"99\"");
            assertEquals(409, stale.getStatus(), "Version không khớp phải trả 409");
            assertEquals(
                    "VERSION_CONFLICT",
                    JSON.readTree(stale.getContentAsString()).get("code").stringValue());

            JsonNode before = getJson(session, uri, 200);
            assertEquals(
                    "Khóa precondition",
                    before.get("name").stringValue(),
                    "Hai lần bị từ chối không được ghi gì");
            assertEquals(0, before.get("version").longValue());

            MockHttpServletResponse renamed = patchRename(session, uri, "Tên mới", "\"0\"");
            assertEquals(202, renamed.getStatus(), "Version đúng phải được chấp nhận");
            awaitOperationSucceeded(
                    jdbc,
                    UUID.fromString(
                            JSON.readTree(renamed.getContentAsString())
                                    .get("operationId")
                                    .stringValue()));

            JsonNode after = getJson(session, uri, 200);
            assertEquals("Tên mới", after.get("name").stringValue());
            assertEquals(1, after.get("version").longValue());

            MockHttpServletResponse outdated = patchRename(session, uri, "Tên khác", "\"0\"");
            assertEquals(
                    409,
                    outdated.getStatus(),
                    "Version cũ sau lần đổi tên thành công phải trả 409");
        } finally {
            deleteFixture(fixture);
        }
    }

    private MockHttpServletResponse patchRename(
            RealSession session, String uri, String name, @Nullable String ifMatch)
            throws Exception {
        var request =
                patch(uri)
                        .cookie(session.cookie())
                        .with(csrf())
                        .header("Idempotency-Key", "teaching-rename-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}");
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mockMvc().perform(request).andReturn().getResponse();
    }
}
