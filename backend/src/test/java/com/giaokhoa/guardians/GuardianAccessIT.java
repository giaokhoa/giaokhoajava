package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng liên kết phụ huynh ở tầng HTTP: phụ huynh tự gửi yêu cầu cho chính mình, chỉ chủ sở
 * hữu/quản lý được duyệt hoặc gỡ, giáo viên chủ nhiệm và giáo viên bộ môn đứng riêng không duyệt
 * được, danh sách chỉ lộ yêu cầu của chính actor (quản lý thấy danh sách cần duyệt), yêu cầu bị từ
 * chối phải gửi mới, và cùng một phiên sau khi liên kết bị gỡ thì lần đọc kết quả tiếp theo bị
 * chặn.
 *
 * <p>Đây là task test-first: route, bảng và service guardian chưa được triển khai nên các test hiện
 * đỏ với 404 ở lời gọi guardian đầu tiên — đỏ đó là có ý nghĩa, không phải lỗi hạ tầng. Các test kỳ
 * vọng 404 cho đối tượng ngoài phạm vi được viết kèm một luồng hợp lệ chạy trước để không xanh giả
 * khi route chưa tồn tại.
 *
 * <p>Ba điểm hợp đồng test này chốt theo tài liệu hiện có và cần đối chiếu khi triển khai: item của
 * danh sách liên kết lộ {@code version} vì {@code GuardianDecision} bắt buộc mang {@code
 * expectedVersion}; học sinh ngoài phạm vi center trả 404 theo quy tắc không tiết lộ sự tồn tại
 * ngoài phạm vi của hợp đồng HTTP; body mang {@code parentUserId} của người khác chưa được hợp đồng
 * chốt là từ chối (400) hay bỏ qua trường lạ (202) nên test chấp nhận cả hai, chỉ chốt chặt không
 * bao giờ tạo liên kết cho phụ huynh khác.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianAccessIT extends GuardianFixture {
    private static final String SCHEMA =
            "guardian_access_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        // Class này chờ operation do worker thật xử lý nên teardown dừng scheduler trước để
        // không còn actor đồng thời nào chạm schema khi DROP.
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Test
    void parentRequestsLinkAndSeesItPendingInOwnList() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            assertEquals(
                    center.parentId().toString(),
                    link.get("parentUserId").stringValue(),
                    "Requester là actor hiện hành, server ghi đúng phụ huynh gửi yêu cầu");
            assertEquals(
                    "REQUESTED",
                    link.get("state").stringValue(),
                    "Liên kết mới ở trạng thái REQUESTED, chưa tự mở quyền");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void managerApprovesLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            JsonNode pending =
                    findLink(listLinks(manager, center.workspaceId()), center.studentX());
            decideSucceeded(manager, center.workspaceId(), pending, "APPROVE");
            assertEquals(
                    "APPROVED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Sau khi quản lý duyệt, liên kết ở trạng thái APPROVED");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void ownerApprovesLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var owner = openSession(center.owner())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            JsonNode pending = findLink(listLinks(owner, center.workspaceId()), center.studentX());
            decideSucceeded(owner, center.workspaceId(), pending, "APPROVE");
            assertEquals("APPROVED", linkStateOf(parent, center.workspaceId(), center.studentX()));
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void rejectedLinkNeedsNewRequest() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode first = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), first, "REJECT");
            JsonNode rejected =
                    findLink(listLinks(parent, center.workspaceId()), center.studentX());
            assertEquals("REJECTED", rejected.get("state").stringValue());
            MockHttpServletResponse secondDecision =
                    decide(
                            manager,
                            center.workspaceId(),
                            linkIdOf(rejected),
                            "APPROVE",
                            rejected.get("version").longValue());
            assertEquals(
                    409,
                    secondDecision.getStatus(),
                    "Request đã bị từ chối là kết thúc; muốn liên kết lại phải gửi yêu cầu mới");
            JsonNode renewed =
                    requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            assertNotEquals(
                    linkIdOf(first),
                    linkIdOf(renewed),
                    "Yêu cầu mới là một liên kết mới, không hồi sinh request đã từ chối");
            assertEquals("REQUESTED", renewed.get("state").stringValue());
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void homeroomTeacherCannotDecideLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var homeroom = openSession(center.homeroom())) {
            UUID classId = createClass(owner, center.workspaceId(), "Lớp chủ nhiệm");
            addRoster(owner, center.workspaceId(), classId, center.studentX());
            assignHomeroom(owner, center.workspaceId(), classId, center.homeroomId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    decide(
                            homeroom,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue());
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên chủ nhiệm không có vai trò quản lý/chủ sở hữu thì không được duyệt"
                            + " liên kết");
            assertEquals(
                    "REQUESTED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Liên kết vẫn chờ duyệt sau lần duyệt bị từ chối");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void subjectTeacherCannotDecideLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var teacher = openSession(center.teacher())) {
            UUID classId = createClass(owner, center.workspaceId(), "Lớp bộ môn");
            UUID rosterId = addRoster(owner, center.workspaceId(), classId, center.studentX());
            SubjectRef subject = firstSubject(owner);
            UUID courseId =
                    createCourse(
                            owner,
                            center.workspaceId(),
                            classId,
                            "Khóa bộ môn",
                            List.of(subject.id()));
            UUID courseSubjectId = courseSubjectId(courseId, subject.id());
            enroll(owner, center.workspaceId(), rosterId, courseSubjectId);
            assignTeacher(owner, center.workspaceId(), courseSubjectId, center.teacherId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    decide(
                            teacher,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue());
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên bộ môn đứng riêng không được duyệt liên kết");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void parentCannotRequestLinkForAnotherParent() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var otherParent = openSession(center.otherParent())) {
            MockHttpServletResponse response =
                    requestLink(
                            parent,
                            center.workspaceId(),
                            "{\"studentUserId\":\""
                                    + center.studentX()
                                    + "\",\"parentUserId\":\""
                                    + center.otherParentId()
                                    + "\"}");
            assertTrue(
                    response.getStatus() == 202 || response.getStatus() == 400,
                    "Body mang parentId của người khác phải bị từ chối hoặc bị bỏ qua, không được"
                            + " tạo liên kết cho phụ huynh khác; status thực tế "
                            + response.getStatus());
            for (JsonNode item : listLinks(otherParent, center.workspaceId())) {
                assertNotEquals(
                        center.studentX().toString(),
                        item.get("studentUserId").stringValue(),
                        "Phụ huynh khác không có liên kết nào từ yêu cầu giả danh này");
            }
            boolean accepted = response.getStatus() == 202;
            if (accepted) {
                JsonNode own = findLink(listLinks(parent, center.workspaceId()), center.studentX());
                assertEquals(
                        center.parentId().toString(),
                        own.get("parentUserId").stringValue(),
                        "Nếu yêu cầu được nhận, liên kết thuộc về chính actor gửi");
            }
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void parentCannotRequestLinkForStudentOutsideCenter() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        KeycloakLogin outsider = createVerifiedUser("guardian-outsider");
        UUID outsiderId = seedAccount(outsider);
        try (var parent = openSession(center.parent())) {
            // Luồng hợp lệ chạy trước để test không xanh giả khi route chưa tồn tại.
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    requestLink(
                            parent,
                            center.workspaceId(),
                            "{\"studentUserId\":\"" + outsiderId + "\"}");
            assertEquals(
                    404,
                    response.getStatus(),
                    "Học sinh không thuộc phạm vi center thì không được tạo liên kết và không lộ"
                            + " sự tồn tại ngoài phạm vi");
        } finally {
            deleteUser(outsider.subject());
            deleteGuardianCenter(center);
        }
    }

    @Test
    void linkListsAreScopedToOwnRequests() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var otherParent = openSession(center.otherParent());
                var manager = openSession(center.manager());
                var teacher = openSession(center.teacher())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            requestLinkSucceeded(otherParent, center.workspaceId(), center.studentY());
            for (JsonNode item : listLinks(parent, center.workspaceId())) {
                assertEquals(
                        center.parentId().toString(),
                        item.get("parentUserId").stringValue(),
                        "Phụ huynh chỉ thấy yêu cầu của chính mình");
            }
            for (JsonNode item : listLinks(otherParent, center.workspaceId())) {
                assertEquals(
                        center.otherParentId().toString(),
                        item.get("parentUserId").stringValue(),
                        "Phụ huynh chỉ thấy yêu cầu của chính mình");
            }
            JsonNode managerItems = listLinks(manager, center.workspaceId());
            findLink(managerItems, center.studentX());
            findLink(managerItems, center.studentY());
            MockHttpServletResponse teacherList = getRaw(teacher, linksPath(center.workspaceId()));
            assertEquals(
                    403,
                    teacherList.getStatus(),
                    "Giáo viên không có quyền xem danh sách liên kết phụ huynh");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void studentCannotRequestGuardianLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var student = openSession(center.students().get(0))) {
            MockHttpServletResponse response =
                    requestLink(
                            student,
                            center.workspaceId(),
                            "{\"studentUserId\":\"" + center.studentY() + "\"}");
            assertEquals(
                    403, response.getStatus(), "Vai trò học sinh không có scope guardian.request");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void decisionForUnknownLinkIsNotFound() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            // Luồng hợp lệ chạy trước để test không xanh giả khi route chưa tồn tại.
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            MockHttpServletResponse response =
                    decide(manager, center.workspaceId(), UUID.randomUUID(), "APPROVE", 1L);
            assertEquals(
                    404, response.getStatus(), "Quyết định trên liên kết không tồn tại trả 404");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void staleDecisionVersionConflicts() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse stale =
                    decide(
                            manager,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue() + 100);
            assertEquals(409, stale.getStatus(), "expectedVersion cũ so với row hiện hành trả 409");
            assertEquals(
                    "REQUESTED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Quyết định xung đột không đổi trạng thái liên kết");
            JsonNode current =
                    findLink(listLinks(manager, center.workspaceId()), center.studentX());
            decideSucceeded(manager, center.workspaceId(), current, "APPROVE");
            assertEquals("APPROVED", linkStateOf(parent, center.workspaceId(), center.studentX()));
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void revokedLinkBlocksSameSessionFromReadingResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            MockHttpServletResponse beforeRevoke =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    200,
                    beforeRevoke.getStatus(),
                    "Liên kết đã duyệt thì phụ huynh đọc được kết quả của con");
            revokeSucceeded(manager, center.workspaceId(), linkIdOf(link));
            MockHttpServletResponse afterRevoke =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    403,
                    afterRevoke.getStatus(),
                    "Cùng phiên cũ sau khi liên kết bị gỡ phải bị chặn ngay, không chờ đăng xuất");
        } finally {
            deleteGuardianCenter(center);
        }
    }
}
