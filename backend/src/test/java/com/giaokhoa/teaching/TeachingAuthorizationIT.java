package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.OidcFormLogin;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hợp đồng quyền giảng dạy ở tầng HTTP: giáo viên chỉ có phạm vi môn–khóa được phân công, chủ nhiệm
 * chỉ giao đề đã duyệt/theo dõi mà không tự chấm/xem đáp án, học sinh chỉ thấy roster môn đã ghi
 * danh, actor center khác không thấy dữ liệu center này, và cùng một phiên sau khi bị thu hồi phân
 * công thì lần gọi tiếp theo bị từ chối trong khi giáo viên còn lại vẫn hoạt động.
 *
 * <p>Đây là task test-first: các route và bảng giảng dạy chưa được triển khai nên test khẳng định
 * đúng hợp đồng HTTP và hiện đỏ với 404 vì route chưa tồn tại — đỏ đó là có ý nghĩa, không phải lỗi
 * hạ tầng. Trường hợp giáo viên tạo lớp center đã được khóa ở test hợp đồng US2 ({@code
 * CenterAdministrationIT.teacherCannotCreateCenterClassEvenWithPersonalPackage}) nên không lặp lại
 * ở đây.
 *
 * <p>Quyền đọc roster của từng môn: hợp đồng chốt "chỉ roster môn đó" nhưng không liệt kê đích danh
 * ai được đọc — test này đọc theo nguồn phân công: giáo viên đúng môn–khóa, chủ nhiệm đúng lớp (chỉ
 * roster lớp, không phải roster môn), chủ sở hữu/quản lý. Nếu đội triển khai quyết khác thì đây là
 * điểm cần đối chiếu lại.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeachingAuthorizationIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "teaching_authz_" + UUID.randomUUID().toString().replace("-", "");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");
    private static final String REDIRECT_URI = "http://127.0.0.1:18888/callback";

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

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext webContext;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired ClientRegistrationRepository clientRegistrations;
    @Autowired SessionRepository<?> sessions;

    @Test
    void teacherReadsOwnSubjectRoster() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacher = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    setup.courseSubject(0),
                    List.of(fixture.teacherAId()));
            enroll(
                    owner,
                    fixture.workspaceId(),
                    setup.classStudents(),
                    List.of(setup.courseSubject(0)));
            assertEquals(
                    3,
                    enrollmentCount(teacher, fixture.workspaceId(), setup.courseSubject(0)),
                    "Giáo viên đúng môn–khóa đọc được roster môn của mình");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void teacherOfOneSubjectCannotAssignTeacherOfAnother() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacher = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    setup.courseSubject(0),
                    List.of(fixture.teacherAId()));
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + setup.courseSubject(1)
                                                    + "/teachers")
                                            .cookie(teacher.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-forbid-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"teacherUserIds\":[\""
                                                            + fixture.teacherBId()
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên môn này không được phân công giáo viên cho môn khác");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void teacherOfOneSubjectCannotReadOtherSubjectRoster() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacher = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    setup.courseSubject(0),
                    List.of(fixture.teacherAId()));
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + setup.courseSubject(1)
                                                    + "/enrollments")
                                            .cookie(teacher.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403, response.getStatus(), "Giáo viên môn này không được đọc roster môn khác");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void teacherCannotReadOtherClassRoster() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacher = openSession(fixture.teacherA())) {
            TeachingSetup first = prepareCourse(owner, fixture, 3);
            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    first.courseSubject(0),
                    List.of(fixture.teacherAId()));
            TeachingSetup second = prepareSecondClass(owner, fixture, 3);
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + second.courseSubject(0)
                                                    + "/enrollments")
                                            .cookie(teacher.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên lớp này không được đọc roster lớp khác cùng center");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomCanReadClassRosterButCannotAssignTeachers() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var homeroom = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignHomeroom(owner, fixture.workspaceId(), setup.classId(), fixture.teacherAId());
            MockHttpServletResponse read =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + setup.classId()
                                                    + "/students")
                                            .cookie(homeroom.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(200, read.getStatus(), "Chủ nhiệm đọc được roster lớp của mình");

            MockHttpServletResponse assign =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + setup.courseSubject(0)
                                                    + "/teachers")
                                            .cookie(homeroom.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-hr-assign-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"teacherUserIds\":[\""
                                                            + fixture.teacherBId()
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    assign.getStatus(),
                    "Chủ nhiệm không được phân công giáo viên cho môn — đó là quyền quản trị");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomCannotAddEnrollments() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var homeroom = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignHomeroom(owner, fixture.workspaceId(), setup.classId(), fixture.teacherAId());
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/enrollments")
                                            .cookie(homeroom.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-hr-enroll-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"classStudentIds\":[\""
                                                            + setup.classStudentId(0)
                                                            + "\"],\"courseSubjectIds\":[\""
                                                            + setup.courseSubject(0)
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(403, response.getStatus(), "Chủ nhiệm không được ghi danh học sinh");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomCannotCreateCourses() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var homeroom = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignHomeroom(owner, fixture.workspaceId(), setup.classId(), fixture.teacherAId());
            List<UUID> subjects = subjectIds(owner, 3);
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + setup.classId()
                                                    + "/courses")
                                            .cookie(homeroom.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-hr-course-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"name\":\"Khóa chủ nhiệm\",\"subjectIds\":[\""
                                                            + subjects.get(0)
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(403, response.getStatus(), "Chủ nhiệm không được tạo khóa");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomCannotAssignHomeroom() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var homeroom = openSession(fixture.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            assignHomeroom(owner, fixture.workspaceId(), setup.classId(), fixture.teacherAId());
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + setup.classId()
                                                    + "/homeroom-assignments")
                                            .cookie(homeroom.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-hr-hr-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"teacherUserId\":\""
                                                            + fixture.teacherBId()
                                                            + "\"}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Chỉ chủ sở hữu/quản lý được chỉ định chủ nhiệm, chủ nhiệm hiện tại không được");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void studentCannotReadUnenrolledSubjectRoster() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var student = openSession(fixture.students().getFirst())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            enroll(
                    owner,
                    fixture.workspaceId(),
                    List.of(setup.classStudentId(0)),
                    List.of(setup.courseSubject(0)));
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + setup.courseSubject(2)
                                                    + "/enrollments")
                                            .cookie(student.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Học sinh không được đọc roster môn mình chưa ghi danh");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void actorFromAnotherCenterCannotReadClass() throws Exception {
        Fixture first = seedTeachingCenter();
        Fixture second = seedTeachingCenter();
        try (var owner = openSession(first.owner());
                var outsider = openSession(second.teacherA())) {
            TeachingSetup setup = prepareCourse(owner, first, 3);
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + first.workspaceId()
                                                    + "/classes/"
                                                    + setup.classId())
                                            .cookie(outsider.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    404,
                    response.getStatus(),
                    "Lớp của center khác không tồn tại đối với actor ngoài center");
        } finally {
            deleteFixture(first);
            deleteFixture(second);
        }
    }

    @Test
    void sameSessionAfterTeacherRevokeIsDenied() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacherA = openSession(fixture.teacherA());
                var teacherB = openSession(fixture.teacherB())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            UUID subject = setup.courseSubject(0);
            assignTeachers(owner, fixture.workspaceId(), subject, List.of(fixture.teacherAId()));
            assignTeachers(owner, fixture.workspaceId(), subject, List.of(fixture.teacherBId()));
            enroll(owner, fixture.workspaceId(), setup.classStudents(), List.of(subject));
            assertEquals(
                    3,
                    enrollmentCount(teacherA, fixture.workspaceId(), subject),
                    "Trước thu hồi, giáo viên đọc được roster môn của mình");

            UUID assignmentId = teachingAssignmentId(subject, fixture.teacherAId());
            UUID operationId =
                    postMutation(
                            owner,
                            "/api/workspaces/"
                                    + fixture.workspaceId()
                                    + "/course-subjects/"
                                    + subject
                                    + "/teachers/"
                                    + assignmentId,
                            "{}",
                            "DELETE");
            awaitOperationSucceeded(jdbc, operationId);

            MockHttpServletResponse denied =
                    mockMvc()
                            .perform(
                                    post("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + subject
                                                    + "/teachers")
                                            .cookie(teacherA.cookie())
                                            .with(csrf())
                                            .header(
                                                    "Idempotency-Key",
                                                    "teaching-revoked-" + UUID.randomUUID())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"teacherUserIds\":[\""
                                                            + fixture.teacherBId()
                                                            + "\"]}"))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    denied.getStatus(),
                    "Cùng phiên sau khi bị thu hồi phân công thì lần gọi tiếp theo bị từ chối");
            assertEquals(
                    3,
                    enrollmentCount(teacherB, fixture.workspaceId(), subject),
                    "Giáo viên còn lại vẫn hoạt động bình thường");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void managerCanAssignHomeroom() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var manager = openSession(fixture.manager());
                var owner = openSession(fixture.owner())) {
            TeachingSetup setup = prepareCourse(owner, fixture, 3);
            UUID operationId =
                    postMutation(
                            manager,
                            "/api/workspaces/"
                                    + fixture.workspaceId()
                                    + "/classes/"
                                    + setup.classId()
                                    + "/homeroom-assignments",
                            "{\"teacherUserId\":\"" + fixture.teacherAId() + "\"}",
                            "POST");
            awaitOperationSucceeded(jdbc, operationId);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM homeroom_assignment"
                                    + " WHERE class_id = ? AND teacher_user_id = ?"
                                    + " AND state = 'ACTIVE'",
                            Integer.class,
                            setup.classId(),
                            fixture.teacherAId()),
                    "Quản lý chỉ định được chủ nhiệm cho lớp");
        } finally {
            deleteFixture(fixture);
        }
    }

    // ---------- Chuỗi thao tác giảng dạy qua API ----------

    private record TeachingSetup(
            UUID classId, UUID courseId, List<UUID> courseSubjects, List<UUID> classStudents) {
        UUID courseSubject(int index) {
            return courseSubjects.get(index);
        }

        UUID classStudentId(int index) {
            return classStudents.get(index);
        }
    }

    private TeachingSetup prepareCourse(RealSession owner, Fixture fixture, int subjectCount)
            throws Exception {
        return prepareCourseInClass(owner, fixture, "Lớp chính", subjectCount);
    }

    private TeachingSetup prepareSecondClass(RealSession owner, Fixture fixture, int subjectCount)
            throws Exception {
        return prepareCourseInClass(owner, fixture, "Lớp thứ hai", subjectCount);
    }

    private TeachingSetup prepareCourseInClass(
            RealSession owner, Fixture fixture, String className, int subjectCount)
            throws Exception {
        UUID classId = createClass(owner, fixture.workspaceId(), className);
        List<UUID> subjects = subjectIds(owner, subjectCount);
        UUID courseId =
                createCourse(owner, fixture.workspaceId(), classId, "Khóa " + className, subjects);
        List<UUID> courseSubjects = new ArrayList<>();
        for (UUID subjectId : subjects) {
            courseSubjects.add(courseSubjectId(courseId, subjectId));
        }
        List<UUID> classStudents = new ArrayList<>();
        for (UUID studentId : fixture.studentIds()) {
            classStudents.add(addRoster(owner, fixture.workspaceId(), classId, studentId));
        }
        return new TeachingSetup(classId, courseId, courseSubjects, classStudents);
    }

    private UUID createClass(RealSession session, UUID workspaceId, String name) throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes",
                        "{\"name\":\"" + name + "\"}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM school_class WHERE workspace_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        name),
                "Lớp phải tồn tại sau khi operation SUCCEEDED");
    }

    private UUID createCourse(
            RealSession session, UUID workspaceId, UUID classId, String name, List<UUID> subjectIds)
            throws Exception {
        String subjects =
                subjectIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/courses",
                        "{\"name\":\"" + name + "\",\"subjectIds\":[" + subjects + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course WHERE workspace_id = ? AND class_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        classId,
                        name),
                "Khóa phải tồn tại sau khi operation SUCCEEDED");
    }

    private UUID addRoster(RealSession session, UUID workspaceId, UUID classId, UUID studentUserId)
            throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students",
                        "{\"studentUserIds\":[\"" + studentUserId + "\"]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM class_student WHERE class_id = ? AND student_user_id = ?",
                        UUID.class,
                        classId,
                        studentUserId),
                "Học sinh phải có trong roster của lớp");
    }

    private void enroll(
            RealSession session,
            UUID workspaceId,
            List<UUID> classStudentIds,
            List<UUID> courseSubjectIds)
            throws Exception {
        String students =
                classStudentIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        String subjects =
                courseSubjectIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/enrollments",
                        "{\"classStudentIds\":["
                                + students
                                + "],\"courseSubjectIds\":["
                                + subjects
                                + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
    }

    private void assignTeachers(
            RealSession session, UUID workspaceId, UUID courseSubjectId, List<UUID> teacherUserIds)
            throws Exception {
        String teachers =
                teacherUserIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/teachers",
                        "{\"teacherUserIds\":[" + teachers + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
    }

    private void assignHomeroom(
            RealSession session, UUID workspaceId, UUID classId, UUID teacherUserId)
            throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/classes/"
                                + classId
                                + "/homeroom-assignments",
                        "{\"teacherUserId\":\"" + teacherUserId + "\"}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
    }

    private List<UUID> subjectIds(RealSession session, int needed) throws Exception {
        JsonNode body = getJson(session, "/api/subjects", 200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh mục môn trả dạng {items}");
        assertTrue(items.isArray(), "Danh mục môn là một mảng");
        assertTrue(
                items.size() >= needed,
                "Danh mục môn phải có ít nhất " + needed + " môn, thực tế " + items.size());
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(UUID.fromString(item.get("id").stringValue()));
            if (ids.size() == needed) {
                break;
            }
        }
        return ids;
    }

    private int enrollmentCount(RealSession session, UUID workspaceId, UUID courseSubjectId)
            throws Exception {
        JsonNode body =
                getJson(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/enrollments",
                        200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh sách ghi danh trả dạng {items}");
        assertTrue(items.isArray(), "Danh sách ghi danh là một mảng");
        return items.size();
    }

    private UUID courseSubjectId(UUID courseId, UUID subjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course_subject WHERE course_id = ? AND subject_id = ?",
                        UUID.class,
                        courseId,
                        subjectId),
                "Môn–khóa phải tồn tại");
    }

    private UUID teachingAssignmentId(UUID courseSubjectId, UUID teacherUserId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM teaching_assignment"
                                + " WHERE course_subject_id = ? AND teacher_user_id = ?",
                        UUID.class,
                        courseSubjectId,
                        teacherUserId),
                "Phân công giảng dạy phải tồn tại");
    }

    private UUID postMutation(RealSession session, String uri, String json, String method)
            throws Exception {
        MockHttpServletResponse response =
                switch (method) {
                    case "POST" ->
                            mockMvc()
                                    .perform(
                                            post(uri)
                                                    .cookie(session.cookie())
                                                    .with(csrf())
                                                    .header(
                                                            "Idempotency-Key",
                                                            "teaching-" + UUID.randomUUID())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(json))
                                    .andReturn()
                                    .getResponse();
                    case "DELETE" ->
                            mockMvc()
                                    .perform(
                                            delete(uri)
                                                    .cookie(session.cookie())
                                                    .with(csrf())
                                                    .header(
                                                            "Idempotency-Key",
                                                            "teaching-" + UUID.randomUUID())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(json))
                                    .andReturn()
                                    .getResponse();
                    default -> throw new IllegalArgumentException("Method không hỗ trợ: " + method);
                };
        assertEquals(202, response.getStatus(), "Mutation phải trả 202 OperationView, URI: " + uri);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("PENDING", body.get("state").stringValue());
        assertFalse(body.get("completed").booleanValue());
        assertFalse(body.get("statusUrl").stringValue().isBlank());
        return UUID.fromString(body.get("operationId").stringValue());
    }

    private JsonNode getJson(RealSession session, String uri, int expectedStatus) throws Exception {
        MockHttpServletResponse response =
                mockMvc().perform(get(uri).cookie(session.cookie())).andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus(), "GET " + uri);
        return JSON.readTree(response.getContentAsString());
    }

    // ---------- Dữ kiện thư mục ----------

    private record KeycloakLogin(String username, String password, String subject, String email) {}

    private record Fixture(
            UUID workspaceId,
            KeycloakLogin owner,
            UUID ownerId,
            KeycloakLogin manager,
            UUID managerId,
            KeycloakLogin teacherA,
            UUID teacherAId,
            KeycloakLogin teacherB,
            UUID teacherBId,
            List<KeycloakLogin> students,
            List<UUID> studentIds) {}

    private Fixture seedTeachingCenter() {
        KeycloakLogin ownerLogin = createVerifiedUser("teaching-owner");
        KeycloakLogin managerLogin = createVerifiedUser("teaching-manager");
        KeycloakLogin teacherALogin = createVerifiedUser("teaching-teacher-a");
        KeycloakLogin teacherBLogin = createVerifiedUser("teaching-teacher-b");
        List<KeycloakLogin> studentLogins =
                List.of(
                        createVerifiedUser("teaching-student-a"),
                        createVerifiedUser("teaching-student-b"),
                        createVerifiedUser("teaching-student-c"));
        UUID ownerId =
                seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
        UUID managerId =
                seedAccount(managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
        UUID teacherAId =
                seedAccount(
                        teacherALogin.subject(), "SELF_REGISTERED", teacherALogin.email(), true);
        UUID teacherBId =
                seedAccount(
                        teacherBLogin.subject(), "SELF_REGISTERED", teacherBLogin.email(), true);
        List<UUID> studentIds = new ArrayList<>();
        for (KeycloakLogin student : studentLogins) {
            studentIds.add(
                    seedAccount(student.subject(), "SELF_REGISTERED", student.email(), true));
        }
        UUID workspaceId = seedCenter(ownerId);
        addMembership(workspaceId, managerId, "MANAGER");
        addMembership(workspaceId, teacherAId, "TEACHER");
        addMembership(workspaceId, teacherBId, "TEACHER");
        for (UUID studentId : studentIds) {
            addMembership(workspaceId, studentId, "STUDENT");
        }
        seedSubscription(workspaceId, -1, 365, ownerId);
        return new Fixture(
                workspaceId,
                ownerLogin,
                ownerId,
                managerLogin,
                managerId,
                teacherALogin,
                teacherAId,
                teacherBLogin,
                teacherBId,
                studentLogins,
                studentIds);
    }

    private void deleteFixture(Fixture fixture) {
        deleteUser(fixture.owner().subject());
        deleteUser(fixture.manager().subject());
        deleteUser(fixture.teacherA().subject());
        deleteUser(fixture.teacherB().subject());
        for (KeycloakLogin student : fixture.students()) {
            deleteUser(student.subject());
        }
    }

    private KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "teaching-password-" + UUID.randomUUID();
        String email = username + "@example.test";
        String subject = createUser(keycloakUser(username, email, true, password));
        return new KeycloakLogin(username, password, subject, email);
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
                    "teaching-user-" + actor,
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

    private UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            for (String role : roles) {
                jdbc.update(
                        "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                        membership,
                        role);
            }
            return null;
        });
        return membership;
    }

    private void seedSubscription(
            UUID workspaceId, int validFromOffsetDays, int expiresOffsetDays, UUID confirmedBy) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(validFromOffsetDays);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresOffsetDays);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    validFrom,
                    expiresAt,
                    "teaching-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    /**
     * Đọc danh sách workspace của actor seed trực tiếp bằng JDBC: account chưa từng bootstrap nên
     * các lời gọi đầu có thể nhận 503 AUTHORIZATION_NOT_READY trong lúc eligibility và projection
     * của tài khoản đang đồng bộ — hành vi fail-closed đúng của sản phẩm. Client thật thử lại có
     * giới hạn với đúng mã lỗi đó rồi mới khẳng định; ngữ nghĩa khẳng định của test không đổi.
     */
    private MockHttpServletResponse getWorkspacesWhenReady(RealSession session) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (true) {
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(get("/api/me/workspaces").cookie(session.cookie()))
                            .andReturn()
                            .getResponse();
            boolean syncing =
                    response.getStatus() == 503
                            && response.getContentAsString().contains("AUTHORIZATION_NOT_READY");
            if (!syncing || Instant.now().isAfter(deadline)) {
                return response;
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for the operation");
        }
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
    }

    private RealSession openSession(KeycloakLogin login) throws Exception {
        RealSession session =
                RealSession.open(
                        login.username(),
                        login.password(),
                        login.subject(),
                        sessionFilter,
                        authorizedClients,
                        clientRegistrations,
                        sessions);
        // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
        // của actor, điều kiện để PDP cho qua các scope nghiệp vụ.
        getWorkspacesWhenReady(session);
        return session;
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> keycloakUser(
            String username, @Nullable String email, boolean emailVerified, String password) {
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("emailVerified", emailVerified);
        body.put("requiredActions", List.of());
        if (email != null) {
            body.put("email", email);
        }
        body.put(
                "credentials",
                List.of(Map.of("type", "password", "value", password, "temporary", false)));
        return body;
    }

    private String createUser(Map<String, Object> body) {
        var response =
                HTTP.post()
                        .uri(admin("/users"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void deleteUser(String userId) {
        deleteResource(admin("/users/" + userId));
    }

    private void deleteResource(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private String masterToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("Master token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    private String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    static String fixture(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }

    /**
     * Phiên Spring thật chứa token Code+PKCE thật của một user TEST. Đăng nhập đi qua form login
     * của Keycloak TEST bằng client BFF của ứng dụng; không dùng Direct Access Grants cho phiên
     * web. Login dùng helper dùng chung {@code OidcFormLogin} (đã biết hai bước identity-first),
     * phần còn lại dựng phiên Spring từ token như bản gốc trong test hợp đồng US2.
     */
    static final class RealSession implements AutoCloseable {
        private final SessionRepository<?> sessions;
        private final Cookie sessionCookie;

        private RealSession(SessionRepository<?> sessions, Cookie sessionCookie) {
            this.sessions = sessions;
            this.sessionCookie = sessionCookie;
        }

        Cookie cookie() {
            return sessionCookie;
        }

        @Override
        public void close() {
            sessions.deleteById(sessionCookie.getValue());
        }

        static RealSession open(
                String username,
                String password,
                String expectedSubject,
                SessionRepositoryFilter<?> filter,
                OAuth2AuthorizedClientRepository clients,
                ClientRegistrationRepository registrations,
                SessionRepository<?> sessions)
                throws Exception {
            var registration =
                    Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
            OidcFormLogin.WebTokens tokens =
                    OidcFormLogin.webTokensViaCodePkce(
                            HTTP, BASE, REALM, REDIRECT_URI, username, password);
            String claims =
                    new String(
                            Base64.getUrlDecoder().decode(tokens.idToken().split("[.]")[1]),
                            StandardCharsets.UTF_8);
            assertEquals(expectedSubject, JSON.readTree(claims).get("sub").stringValue());
            Instant issued = Instant.now();
            var idToken =
                    new OidcIdToken(
                            tokens.idToken(),
                            issued,
                            issued.plusSeconds(300),
                            Map.of(
                                    "iss",
                                    BASE + "/realms/" + REALM,
                                    "sub",
                                    expectedSubject,
                                    "aud",
                                    List.of(registration.getClientId())));
            var authentication =
                    new OAuth2AuthenticationToken(
                            new DefaultOidcUser(List.of(), idToken, "sub"), List.of(), "keycloak");
            var authorized =
                    new OAuth2AuthorizedClient(
                            registration,
                            authentication.getName(),
                            new OAuth2AccessToken(
                                    OAuth2AccessToken.TokenType.BEARER,
                                    tokens.accessToken(),
                                    issued,
                                    issued.plusSeconds(300)),
                            new OAuth2RefreshToken(tokens.refreshToken(), issued));
            var request = new MockHttpServletRequest("GET", "/");
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request,
                    response,
                    (wrapped, reply) -> {
                        var servletRequest = (HttpServletRequest) wrapped;
                        servletRequest
                                .getSession(true)
                                .setAttribute(
                                        HttpSessionSecurityContextRepository
                                                .SPRING_SECURITY_CONTEXT_KEY,
                                        new SecurityContextImpl(authentication));
                        clients.saveAuthorizedClient(
                                authorized,
                                authentication,
                                servletRequest,
                                (HttpServletResponse) reply);
                    });
            Cookie sessionCookie = Objects.requireNonNull(response.getCookie("SESSION"));
            assertNotNull(
                    sessions.findById(sessionCookie.getValue()), "Session must persist in JDBC");
            return new RealSession(sessions, sessionCookie);
        }
    }
}
