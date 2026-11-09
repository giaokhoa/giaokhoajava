package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng cấu trúc lớp–khóa–môn/roster/ghi danh/phân công ở tầng HTTP: tạo lớp/khóa, danh mục môn,
 * roster, ghi danh từng môn (học sinh chỉ vào môn đã chọn), thêm môn không tự ghi danh, hai giáo
 * viên cùng môn, một môn ở hai khóa là hai phạm vi khác nhau, và ràng buộc FK chéo workspace/lớp.
 *
 * <p>Đây là task test-first: các route và bảng giảng dạy chưa được triển khai nên test khẳng định
 * đúng hợp đồng HTTP và hiện đỏ với 404 vì route chưa tồn tại — đỏ đó là có ý nghĩa, không phải lỗi
 * hạ tầng. Hai khóa biên an ninh (nặc danh bị chuyển hướng đăng nhập, mutation thiếu CSRF bị chặn)
 * và biên quyền tạo lớp khi gói hết hạn thì xanh ngay vì route tạo lớp đã có stub thực thi đúng
 * biên đó.
 *
 * <p>Trạng thái kỳ vọng từng test chưa được khóa bằng mã field danh sách roster/enrollment (hợp
 * đồng chưa chốt tên field của item): các khẳng định danh sách chỉ dùng số lượng item, không đọc
 * tên field item. Tên field của {@code CourseView} và danh mục môn thì hợp đồng đã chốt nên khẳng
 * định trực tiếp.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("e2e-backend")
class TeachingStructureIT extends TeachingFixture {

    // Danh tính Keycloak + tài khoản seed một lần cho cả class (phần seed đắt). Center chuẩn
    // cũng dựng một lần và dùng chung: cô lập nằm ở phân vùng thực thể con — tên lớp của mỗi
    // test vốn đã duy nhất (Lớp 1A, 1B, 2A, ...) và mỗi test chỉ khẳng định trên class/course
    // của chính nó, không test nào sửa membership hay gói của center dùng chung. Riêng biến
    // thể gói hết hạn là một center thứ hai dựng một lần cho đúng một test cần nó.
    private SharedIdentities sharedIdentities;
    private Fixture sharedCenter;
    private Fixture sharedExpiredCenter;

    @BeforeAll
    void seedSharedIdentitiesOnce() {
        sharedIdentities = seedSharedIdentities();
        sharedCenter = seedCenterFixture(sharedIdentities);
        sharedExpiredCenter = seedCenterFixture(sharedIdentities, -60, -1);
    }

    @AfterAll
    void deleteSharedIdentitiesOnce() {
        deleteSharedIdentities(sharedIdentities);
    }

    @Test
    void ownerCreatesClassReturnsPendingOperation() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 1A");

            MockHttpServletResponse read =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + classId)
                                            .cookie(session.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(200, read.getStatus(), "Đọc metadata lớp vừa tạo phải thành công");
            JsonNode body = JSON.readTree(read.getContentAsString());
            assertEquals(classId.toString(), body.get("id").stringValue());
            assertEquals("Lớp 1A", body.get("name").stringValue());
        }
    }

    @Test
    void managerCreatesClassReturnsPendingOperation() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.manager())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 1B");
            assertNotNull(classId, "Quản lý tạo được lớp trong center của mình");
        }
    }

    @Test
    void expiredSubscriptionBlocksClassCreate() throws Exception {
        // Fixture này chỉ có kỳ gói đã hết hạn (không kỳ nào còn hiệu lực): biên quyền
        // của route tạo lớp phải từ chối 403. Bảng lớp chưa tồn tại nên test này không
        // khẳng định thêm "không để lại lớp" bằng truy vấn DB.
        Fixture fixture = sharedExpiredCenter;
        try (var session = openSession(fixture.owner())) {
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/" + fixture.workspaceId() + "/classes")
                                            .cookie(session.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-exp-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"name\":\"Lớp hết gói\"}"))
                            .andReturn()
                            .getResponse();
            assertEquals(403, response.getStatus(), "Gói hết hạn chặn tạo lớp mới");
        }
    }

    @Test
    void courseRequiresAtLeastOneSubject() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 2A");
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + classId
                                                    + "/courses")
                                            .cookie(session.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-empty-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"name\":\"Khóa rỗng\",\"subjectIds\":[]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(400, response.getStatus(), "Khóa phải có ít nhất một môn");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM course WHERE name = 'Khóa rỗng'", Integer.class),
                    "Khóa không hợp lệ không được ghi");
        }
    }

    @Test
    void subjectsCatalogListsReferenceSubjects() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            List<UUID> subjects = subjectIds(session, 3);
            assertEquals(3, subjects.size(), "Danh mục môn tối thiểu phải có đủ ba môn");
            JsonNode body = getJson(session, "/api/subjects", 200);
            for (JsonNode item : body.get("items")) {
                assertFalse(item.get("code").stringValue().isBlank(), "Môn có mã danh mục");
                assertFalse(item.get("name").stringValue().isBlank(), "Môn có tên hiển thị");
            }
        }
    }

    @Test
    void courseWithThreeSubjectsHasCourseView() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 3A");
            List<UUID> subjects = subjectIds(session, 3);
            UUID courseId =
                    createCourse(session, fixture.workspaceId(), classId, "Khóa ba môn", subjects);

            MockHttpServletResponse read =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/courses/"
                                                    + courseId)
                                            .cookie(session.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(200, read.getStatus(), "Đọc khóa vừa tạo phải thành công");
            JsonNode body = JSON.readTree(read.getContentAsString());
            assertEquals(classId.toString(), body.get("classId").stringValue());
            assertEquals("Khóa ba môn", body.get("name").stringValue());
            assertEquals(3, body.get("subjects").size(), "Khóa giữ đủ ba môn");
            for (JsonNode subject : body.get("subjects")) {
                assertTrue(
                        subjects.stream()
                                .map(id -> id.toString())
                                .anyMatch(id -> id.equals(subject.get("subjectId").stringValue())),
                        "Môn trong khóa là môn đã chọn");
            }
        }
    }

    @Test
    void studentChoosesOnlySelectedSubjects() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 4A");
            List<UUID> subjects = subjectIds(session, 3);
            UUID courseId =
                    createCourse(
                            session, fixture.workspaceId(), classId, "Khóa chọn môn", subjects);
            UUID firstStudent = fixture.studentIds().getFirst();
            UUID classStudentId = addRoster(session, fixture.workspaceId(), classId, firstStudent);
            List<UUID> chosen =
                    List.of(
                            courseSubjectId(courseId, subjects.get(0)),
                            courseSubjectId(courseId, subjects.get(1)));
            enroll(session, fixture.workspaceId(), List.of(classStudentId), chosen);

            assertEquals(
                    1,
                    enrollmentCount(session, fixture.workspaceId(), chosen.get(0)),
                    "Môn đã chọn có học sinh");
            assertEquals(
                    1,
                    enrollmentCount(session, fixture.workspaceId(), chosen.get(1)),
                    "Môn đã chọn có học sinh");
            assertEquals(
                    0,
                    enrollmentCount(
                            session,
                            fixture.workspaceId(),
                            courseSubjectId(courseId, subjects.get(2))),
                    "Môn không chọn không có học sinh này");
        }
    }

    @Test
    void addingSubjectDoesNotAutoEnroll() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 5A");
            // Khóa 2 môn + thêm môn thứ 3: đủ để chứng minh thêm môn không tự ghi danh,
            // mà không chốt danh mục tối thiểu 4 môn như hợp đồng không quy định.
            List<UUID> subjects = subjectIds(session, 3);
            UUID courseId =
                    createCourse(
                            session,
                            fixture.workspaceId(),
                            classId,
                            "Khóa thêm môn",
                            subjects.subList(0, 2));
            UUID classStudentId =
                    addRoster(
                            session,
                            fixture.workspaceId(),
                            classId,
                            fixture.studentIds().getFirst());
            List<UUID> current =
                    List.of(
                            courseSubjectId(courseId, subjects.get(0)),
                            courseSubjectId(courseId, subjects.get(1)));
            enroll(session, fixture.workspaceId(), List.of(classStudentId), current);

            UUID added =
                    addCourseSubject(session, fixture.workspaceId(), courseId, subjects.get(2));
            assertEquals(
                    0,
                    enrollmentCount(session, fixture.workspaceId(), added),
                    "Môn thêm sau không tự ghi danh học sinh đã ghi danh trước đó");
            assertEquals(
                    1,
                    enrollmentCount(session, fixture.workspaceId(), current.get(0)),
                    "Ghi danh môn hiện có không đổi khi thêm môn mới");
        }
    }

    @Test
    void twoTeachersShareOneCourseSubject() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 6A");
            List<UUID> subjects = subjectIds(session, 1);
            UUID courseId =
                    createCourse(
                            session, fixture.workspaceId(), classId, "Khóa hai thầy", subjects);
            UUID courseSubject = courseSubjectId(courseId, subjects.get(0));
            assignTeachers(
                    session,
                    fixture.workspaceId(),
                    courseSubject,
                    List.of(fixture.teacherAId(), fixture.teacherBId()));
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM teaching_assignment"
                                    + " WHERE course_subject_id = ? AND state = 'ACTIVE'",
                            Integer.class,
                            courseSubject),
                    "Hai giáo viên cùng phụ trách một môn–khóa");
        }
    }

    @Test
    void sameSubjectInTwoCoursesHasDifferentScopeIdentity() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 7A");
            List<UUID> subjects = subjectIds(session, 1);
            UUID first =
                    createCourse(session, fixture.workspaceId(), classId, "Khóa một", subjects);
            UUID second =
                    createCourse(session, fixture.workspaceId(), classId, "Khóa hai", subjects);
            UUID firstSubject = courseSubjectId(first, subjects.get(0));
            UUID secondSubject = courseSubjectId(second, subjects.get(0));
            assertNotEquals(
                    firstSubject,
                    secondSubject,
                    "Cùng môn ở hai khóa là hai phạm vi quyền khác nhau");
            assignTeachers(
                    session, fixture.workspaceId(), firstSubject, List.of(fixture.teacherAId()));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM teaching_assignment"
                                    + " WHERE course_subject_id = ? AND state = 'ACTIVE'",
                            Integer.class,
                            secondSubject),
                    "Phân công ở khóa một không lan sang khóa hai");
        }
    }

    @Test
    void enrollmentBatchAcrossClassesIsRejected() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID firstClass = createClass(session, fixture.workspaceId(), "Lớp 8A");
            UUID secondClass = createClass(session, fixture.workspaceId(), "Lớp 8B");
            List<UUID> subjects = subjectIds(session, 1);
            UUID secondCourse =
                    createCourse(session, fixture.workspaceId(), secondClass, "Khóa 8B", subjects);
            UUID classStudentId =
                    addRoster(
                            session,
                            fixture.workspaceId(),
                            firstClass,
                            fixture.studentIds().getFirst());
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/enrollments")
                                            .cookie(session.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-mix-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"classStudentIds\":[\""
                                                            + classStudentId
                                                            + "\"],\"courseSubjectIds\":[\""
                                                            + courseSubjectId(
                                                                    secondCourse, subjects.get(0))
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    400, response.getStatus(), "Ghi danh trộn hai lớp khác nhau phải bị từ chối");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM enrollment WHERE class_student_id = ?",
                            Integer.class,
                            classStudentId),
                    "Lô ghi danh không hợp lệ không được ghi phần nào");
        }
    }

    @Test
    void enrollmentBatchAcrossWorkspacesIsRejected() throws Exception {
        Fixture first = sharedCenter;
        Fixture second = seedTeachingCenter();
        try (var session = openSession(first.owner())) {
            UUID classId = createClass(session, first.workspaceId(), "Lớp 9A");
            List<UUID> subjects = subjectIds(session, 1);
            UUID classStudentId =
                    addRoster(session, first.workspaceId(), classId, first.studentIds().getFirst());
            UUID foreignSubject;
            try (var other = openSession(second.owner())) {
                UUID otherClass = createClass(other, second.workspaceId(), "Lớp 9B");
                UUID otherCourse =
                        createCourse(other, second.workspaceId(), otherClass, "Khóa 9B", subjects);
                foreignSubject = courseSubjectId(otherCourse, subjects.get(0));
            }
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/" + first.workspaceId() + "/enrollments")
                                            .cookie(session.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-xws-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"classStudentIds\":[\""
                                                            + classStudentId
                                                            + "\"],\"courseSubjectIds\":[\""
                                                            + foreignSubject
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    400,
                    response.getStatus(),
                    "Lô ghi danh trộn workspace khác vi phạm invariant cùng lớp/workspace");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM enrollment WHERE class_student_id = ?",
                            Integer.class,
                            classStudentId),
                    "Lô ghi danh không hợp lệ không được ghi phần nào");
        } finally {
            deleteFixture(second);
        }
    }

    @Test
    void rosterAddRequiresCenterMembership() throws Exception {
        Fixture fixture = sharedCenter;
        KeycloakLogin outsiderLogin = createVerifiedUser("teaching-outsider");
        UUID outsider;
        try {
            outsider =
                    seedAccount(
                            outsiderLogin.subject(),
                            "SELF_REGISTERED",
                            outsiderLogin.email(),
                            true);
            try (var session = openSession(fixture.owner())) {
                UUID classId = createClass(session, fixture.workspaceId(), "Lớp 10A");
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/"
                                                        + fixture.workspaceId()
                                                        + "/classes/"
                                                        + classId
                                                        + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "teaching-nomember-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"studentUserIds\":[\""
                                                                + outsider
                                                                + "\"]}"))
                                .andReturn()
                                .getResponse();
                assertEquals(
                        400,
                        response.getStatus(),
                        "Người không phải thành viên center không được vào roster");
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM class_student"
                                        + " WHERE class_id = ? AND student_user_id = ?",
                                Integer.class,
                                classId,
                                outsider),
                        "Roster không hợp lệ không được ghi");
            }
        } finally {
            deleteUser(outsiderLogin.subject());
        }
    }

    @Test
    void revokeRosterKeepsHistoryAndRevokesEnrollments() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 11A");
            List<UUID> subjects = subjectIds(session, 1);
            UUID courseId =
                    createCourse(session, fixture.workspaceId(), classId, "Khóa 11A", subjects);
            UUID classStudentId =
                    addRoster(
                            session,
                            fixture.workspaceId(),
                            classId,
                            fixture.studentIds().getFirst());
            enroll(
                    session,
                    fixture.workspaceId(),
                    List.of(classStudentId),
                    List.of(courseSubjectId(courseId, subjects.get(0))));
            UUID operationId =
                    postMutation(
                            session,
                            "/api/workspaces/"
                                    + fixture.workspaceId()
                                    + "/classes/"
                                    + classId
                                    + "/students/"
                                    + classStudentId,
                            "{}",
                            "DELETE");
            awaitOperationSucceeded(jdbc, operationId);

            assertEquals(
                    "REVOKED",
                    jdbc.queryForObject(
                            "SELECT state FROM class_student WHERE id = ?",
                            String.class,
                            classStudentId),
                    "Roster bị thu hồi chuyển trạng thái chứ không bị xóa");
            assertNotNull(
                    jdbc.queryForObject(
                            "SELECT revoked_at FROM class_student WHERE id = ?",
                            OffsetDateTime.class,
                            classStudentId),
                    "Roster bị thu hồi phải ghi thời điểm thu hồi");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM enrollment"
                                    + " WHERE class_student_id = ? AND state = 'ACTIVE'",
                            Integer.class,
                            classStudentId),
                    "Thu hồi roster phải thu hồi ghi danh phụ thuộc");
        }
    }

    @Test
    void anonymousClassListRedirectsToLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(get("/api/workspaces/" + UUID.randomUUID() + "/classes"))
                        .andReturn()
                        .getResponse();
        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"),
                "Chưa đăng nhập thì được hướng dẫn đăng nhập, không nhận dữ liệu nghiệp vụ");
    }

    @Test
    void classMutationWithoutCsrfIsRejected() throws Exception {
        Fixture fixture = sharedCenter;
        try (var session = openSession(fixture.owner())) {
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/" + fixture.workspaceId() + "/classes")
                                            .cookie(session.cookie())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-nocsrf-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"name\":\"Lớp không CSRF\"}"))
                            .andReturn()
                            .getResponse();
            assertEquals(403, response.getStatus(), "Mutation trình duyệt thiếu CSRF bị chặn");
        }
    }

    // ---------- Chuỗi thao tác giảng dạy qua API ----------

    private UUID addCourseSubject(
            RealSession session, UUID workspaceId, UUID courseId, UUID subjectId) throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/courses/" + courseId + "/subjects",
                        "{\"subjectIds\":[\"" + subjectId + "\"]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return courseSubjectId(courseId, subjectId);
    }
}
