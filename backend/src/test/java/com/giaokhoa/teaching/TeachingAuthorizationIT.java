package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
class TeachingAuthorizationIT extends TeachingFixture {
    private static final String SCHEMA =
            "teaching_authz_" + UUID.randomUUID().toString().replace("-", "");

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

    @Test
    void teacherWithSecondAssignmentReadsBothSubjectRosters() throws Exception {
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
                    List.of(setup.courseSubject(0), setup.courseSubject(1)));
            // Phân công bổ sung mở thêm đúng môn được gắn, không mở các môn còn lại.
            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    setup.courseSubject(1),
                    List.of(fixture.teacherAId()));
            assertEquals(
                    3,
                    enrollmentCount(teacher, fixture.workspaceId(), setup.courseSubject(0)),
                    "Giáo viên đọc được roster môn phân công đầu tiên");
            assertEquals(
                    3,
                    enrollmentCount(teacher, fixture.workspaceId(), setup.courseSubject(1)),
                    "Giáo viên đọc được roster môn nhận qua phân công bổ sung");
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/course-subjects/"
                                                    + setup.courseSubject(2)
                                                    + "/enrollments")
                                            .cookie(teacher.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Môn chưa được phân công vẫn bị từ chối sau phân công bổ sung");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomOfOneClassCannotReadOtherClassRoster() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var homeroom = openSession(fixture.teacherA())) {
            TeachingSetup first = prepareCourse(owner, fixture, 3);
            assignHomeroom(owner, fixture.workspaceId(), first.classId(), fixture.teacherAId());
            TeachingSetup second = prepareSecondClass(owner, fixture, 3);
            assertEquals(
                    3,
                    getJson(
                                    homeroom,
                                    "/api/workspaces/"
                                            + fixture.workspaceId()
                                            + "/classes/"
                                            + first.classId()
                                            + "/students",
                                    200)
                            .get("items")
                            .size(),
                    "Chủ nhiệm đọc được roster lớp của mình");
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/workspaces/"
                                                    + fixture.workspaceId()
                                                    + "/classes/"
                                                    + second.classId()
                                                    + "/students")
                                            .cookie(homeroom.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(
                    403,
                    response.getStatus(),
                    "Chủ nhiệm lớp này không được đọc roster lớp khác dù cùng trung tâm");
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
}
