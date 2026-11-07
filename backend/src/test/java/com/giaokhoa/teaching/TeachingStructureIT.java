package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.springframework.http.HttpHeaders;
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
class TeachingStructureIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "teaching_struct_" + UUID.randomUUID().toString().replace("-", "");
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
    void ownerCreatesClassReturnsPendingOperation() throws Exception {
        Fixture fixture = seedTeachingCenter();
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void managerCreatesClassReturnsPendingOperation() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.manager())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 1B");
            assertNotNull(classId, "Quản lý tạo được lớp trong center của mình");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void expiredSubscriptionBlocksClassCreate() throws Exception {
        // Fixture này chỉ có kỳ gói đã hết hạn (không kỳ nào còn hiệu lực): biên quyền
        // của route tạo lớp phải từ chối 403. Bảng lớp chưa tồn tại nên test này không
        // khẳng định thêm "không để lại lớp" bằng truy vấn DB.
        Fixture fixture = seedTeachingCenterWithSubscription(-60, -1);
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void courseRequiresAtLeastOneSubject() throws Exception {
        Fixture fixture = seedTeachingCenter();
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void subjectsCatalogListsReferenceSubjects() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            List<UUID> subjects = subjectIds(session, 3);
            assertEquals(3, subjects.size(), "Danh mục môn tối thiểu phải có đủ ba môn");
            JsonNode body = getJson(session, "/api/subjects", 200);
            for (JsonNode item : body.get("items")) {
                assertFalse(item.get("code").stringValue().isBlank(), "Môn có mã danh mục");
                assertFalse(item.get("name").stringValue().isBlank(), "Môn có tên hiển thị");
            }
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void courseWithThreeSubjectsHasCourseView() throws Exception {
        Fixture fixture = seedTeachingCenter();
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
                                .map(UUID::toString)
                                .anyMatch(id -> id.equals(subject.get("subjectId").stringValue())),
                        "Môn trong khóa là môn đã chọn");
            }
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void studentChoosesOnlySelectedSubjects() throws Exception {
        Fixture fixture = seedTeachingCenter();
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void addingSubjectDoesNotAutoEnroll() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 5A");
            List<UUID> subjects = subjectIds(session, 4);
            UUID courseId =
                    createCourse(
                            session,
                            fixture.workspaceId(),
                            classId,
                            "Khóa thêm môn",
                            subjects.subList(0, 3));
            UUID classStudentId =
                    addRoster(
                            session,
                            fixture.workspaceId(),
                            classId,
                            fixture.studentIds().getFirst());
            List<UUID> current =
                    List.of(
                            courseSubjectId(courseId, subjects.get(0)),
                            courseSubjectId(courseId, subjects.get(1)),
                            courseSubjectId(courseId, subjects.get(2)));
            enroll(session, fixture.workspaceId(), List.of(classStudentId), current);

            UUID added =
                    addCourseSubject(session, fixture.workspaceId(), courseId, subjects.get(3));
            assertEquals(
                    0,
                    enrollmentCount(session, fixture.workspaceId(), added),
                    "Môn thêm sau không tự ghi danh học sinh đã ghi danh trước đó");
            assertEquals(
                    1,
                    enrollmentCount(session, fixture.workspaceId(), current.get(0)),
                    "Ghi danh môn hiện có không đổi khi thêm môn mới");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void twoTeachersShareOneCourseSubject() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 6A");
            List<UUID> subjects = subjectIds(session, 3);
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void sameSubjectInTwoCoursesHasDifferentScopeIdentity() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 7A");
            List<UUID> subjects = subjectIds(session, 3);
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void enrollmentBatchAcrossClassesIsRejected() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID firstClass = createClass(session, fixture.workspaceId(), "Lớp 8A");
            UUID secondClass = createClass(session, fixture.workspaceId(), "Lớp 8B");
            List<UUID> subjects = subjectIds(session, 3);
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
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void enrollmentBatchAcrossWorkspacesIsRejected() throws Exception {
        Fixture first = seedTeachingCenter();
        Fixture second = seedTeachingCenter();
        try (var session = openSession(first.owner())) {
            UUID classId = createClass(session, first.workspaceId(), "Lớp 9A");
            List<UUID> subjects = subjectIds(session, 3);
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
            deleteFixture(first);
            deleteFixture(second);
        }
    }

    @Test
    void rosterAddRequiresCenterMembership() throws Exception {
        Fixture fixture = seedTeachingCenter();
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
            deleteFixture(fixture);
        }
    }

    @Test
    void revokeRosterKeepsHistoryAndRevokesEnrollments() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var session = openSession(fixture.owner())) {
            UUID classId = createClass(session, fixture.workspaceId(), "Lớp 11A");
            List<UUID> subjects = subjectIds(session, 3);
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
        } finally {
            deleteFixture(fixture);
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
        Fixture fixture = seedTeachingCenter();
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
        } finally {
            deleteFixture(fixture);
        }
    }

    // ---------- Chuỗi thao tác giảng dạy qua API ----------

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

    private UUID addRoster(RealSession session, UUID workspaceId, UUID classId, UUID studentUserId)
            throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students",
                        "{\"studentUserIds\":[\"" + studentUserId + "\"]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return classStudentId(classId, studentUserId);
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

    private UUID classStudentId(UUID classId, UUID studentUserId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM class_student WHERE class_id = ? AND student_user_id = ?",
                        UUID.class,
                        classId,
                        studentUserId),
                "Học sinh phải có trong roster của lớp");
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
        return seedTeachingCenterWithSubscription(-1, 365);
    }

    private Fixture seedTeachingCenterWithSubscription(
            int validFromOffsetDays, int expiresOffsetDays) {
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
        seedSubscription(workspaceId, validFromOffsetDays, expiresOffsetDays, ownerId);
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
