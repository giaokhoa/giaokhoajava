package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import tools.jackson.databind.JsonNode;

/**
 * Nhánh giảng dạy cá nhân của US6 ở tầng HTTP: lớp personal dùng owner và enrollment, không dùng
 * center_membership hay role nghề nghiệp. Owner có gói tổ chức lớp/khóa/roster/ghi danh, giao bài
 * và chấm/công bố cho người học đã ghi danh; giáo viên center không gói vẫn biên soạn được tại
 * center nhưng không mở được kho/lớp cá nhân, và rời center không làm mất quyền cá nhân.
 *
 * <p>Đây là test viết trước phần implementation còn thiếu của US6. Kiểm chứng tại thời điểm viết:
 * route giảng dạy trên personal workspace đã chạy cho owner có gói (tổ chức lớp/khóa/roster/ghi
 * danh và rời center đều xanh), nhưng hai khoảng trống còn đỏ đúng lý do — tạo lớp personal chưa bị
 * cổng gói chặn (chưa có gói vẫn nhận 202 thay vì 403) và các sample action ASSIGN_ACTIVITY,
 * SUBMIT_ACTIVITY, GRADE_RESULT, PUBLISH_RESULT chưa được cài đặt (400 UNSUPPORTED_SAMPLE_ACTION).
 * Đỏ đó là bằng chứng red có ý nghĩa của task test-first, không phải lỗi fixture hay môi trường.
 */
@SpringBootTest
class PersonalClassAuthorizationIT extends TeachingFixture {
    private static final String SCHEMA =
            "personal_class_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup(@Autowired ApplicationContext context) {
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Test
    void ownerWithPlanOrganizesPersonalClassCourseRosterAndEnrollment() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("personal-owner");
        KeycloakLogin studentLogin = createVerifiedUser("personal-student");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID studentId =
                    seedAccount(
                            studentLogin.subject(), "SELF_REGISTERED", studentLogin.email(), true);
            UUID personal = personalWorkspaceOf(ownerId);
            seedSubscription(personal, -1, 365, ownerId);

            try (var owner = openSession(ownerLogin)) {
                UUID classId = createClass(owner, personal, "Lớp cá nhân A");
                List<UUID> subjects = subjectIds(owner, 1);
                UUID courseId = createCourse(owner, personal, classId, "Khóa cá nhân A", subjects);
                UUID courseSubjectId = courseSubjectId(courseId, subjects.getFirst());
                UUID classStudentId = addRoster(owner, personal, classId, studentId);
                enroll(owner, personal, List.of(classStudentId), List.of(courseSubjectId));

                assertEquals(
                        1,
                        enrollmentCount(owner, personal, courseSubjectId),
                        "Ghi danh personal phải đọc lại được qua route");
            }
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership WHERE workspace_id = ?",
                            Integer.class,
                            personal),
                    "Lớp personal không được tạo center_membership");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership WHERE user_id = ?",
                            Integer.class,
                            studentId),
                    "Người học personal không nhận membership hay role nghề nghiệp toàn cục");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(studentLogin.subject());
        }
    }

    @Test
    void ownerWithoutPlanCannotCreatePersonalClass() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("personal-no-plan");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID personal = personalWorkspaceOf(ownerId);

            try (var owner = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        postRaw(
                                owner,
                                "/api/workspaces/" + personal + "/classes",
                                "{\"name\":\"Lớp chưa có gói\"}");
                assertEquals(
                        403,
                        response.getStatus(),
                        "Chưa có gói cá nhân thì tạo lớp personal phải bị từ chối");
            }
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM school_class WHERE workspace_id = ?",
                            Integer.class,
                            personal),
                    "Lời gọi bị từ chối không được để lại lớp");
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void centerTeacherWithoutPersonalPlanAuthorsAtCenterButNotPersonal() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("us6-center-owner");
        KeycloakLogin teacherLogin = createVerifiedUser("us6-center-teacher");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacherId =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(ownerId);
            addMembership(center, teacherId, "TEACHER");
            seedSubscription(center, -1, 365, ownerId);
            UUID teacherPersonal = personalWorkspaceOf(teacherId);

            UUID courseSubjectId;
            List<UUID> subjects;
            try (var owner = openSession(ownerLogin)) {
                UUID classId = createClass(owner, center, "Lớp center US6");
                subjects = subjectIds(owner, 1);
                UUID courseId = createCourse(owner, center, classId, "Khóa center US6", subjects);
                courseSubjectId = courseSubjectId(courseId, subjects.getFirst());
                assignTeachers(owner, center, courseSubjectId, List.of(teacherId));
            }

            try (var teacher = openSession(teacherLogin)) {
                JsonNode created =
                        sampleActionExpectCreated(
                                teacher,
                                center,
                                "{\"action\":\"CREATE_RESOURCE\",\"courseSubjectId\":\""
                                        + courseSubjectId
                                        + "\",\"subjectId\":\""
                                        + subjects.getFirst()
                                        + "\",\"kind\":\"EXAM\",\"label\":\"Đề tại center\"}");
                assertEquals("SUCCEEDED", created.get("state").stringValue());

                MockHttpServletResponse personalClass =
                        postRaw(
                                teacher,
                                "/api/workspaces/" + teacherPersonal + "/classes",
                                "{\"name\":\"Lớp cá nhân của giáo viên\"}");
                assertEquals(
                        403,
                        personalClass.getStatus(),
                        "Membership center không mở quyền tạo lớp personal khi chưa có gói");
                MockHttpServletResponse personalResource =
                        sampleAction(teacher, teacherPersonal, createResourceJson(), 403);
                assertEquals(
                        403,
                        personalResource.getStatus(),
                        "Membership center không mở quyền soạn học liệu personal khi chưa có gói");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    @Test
    void personalPlanHolderCannotCreateClassAtCenter() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("us6-plan-center-owner");
        KeycloakLogin userLogin = createVerifiedUser("us6-plan-user");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID userId =
                    seedAccount(userLogin.subject(), "SELF_REGISTERED", userLogin.email(), true);
            UUID center = seedCenter(ownerId);
            addMembership(center, userId, "STUDENT");
            seedSubscription(center, -1, 365, ownerId);
            UUID personal = personalWorkspaceOf(userId);
            seedSubscription(personal, -1, 365, userId);

            try (var user = openSession(userLogin)) {
                MockHttpServletResponse atCenter =
                        postRaw(
                                user,
                                "/api/workspaces/" + center + "/classes",
                                "{\"name\":\"Lớp trái quyền tại center\"}");
                assertEquals(
                        403,
                        atCenter.getStatus(),
                        "Có gói personal nhưng không phải owner/manager thì không tạo lớp tại center");

                UUID classId = createClass(user, personal, "Lớp cá nhân của người có gói");
                assertNotNull(classId, "Cùng actor phải tạo được lớp tại personal workspace");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(userLogin.subject());
        }
    }

    @Test
    void ownerAssignsAndEnrolledStudentSubmitsInPersonalClass() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("personal-assign-owner");
        KeycloakLogin studentLogin = createVerifiedUser("personal-assign-student");
        KeycloakLogin outsiderLogin = createVerifiedUser("personal-assign-outsider");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID studentId =
                    seedAccount(
                            studentLogin.subject(), "SELF_REGISTERED", studentLogin.email(), true);
            UUID outsiderId =
                    seedAccount(
                            outsiderLogin.subject(),
                            "SELF_REGISTERED",
                            outsiderLogin.email(),
                            true);
            UUID personal = personalWorkspaceOf(ownerId);
            seedSubscription(personal, -1, 365, ownerId);
            PersonalChain chain = seedPersonalChain(personal, studentId);
            ResourceStub stub = seedResourceStub(personal, ownerId, chain.subjectId());

            UUID activityId;
            try (var owner = openSession(ownerLogin)) {
                JsonNode assigned =
                        sampleActionExpectCreated(
                                owner,
                                personal,
                                "{\"action\":\"ASSIGN_ACTIVITY\",\"courseSubjectId\":\""
                                        + chain.courseSubjectId()
                                        + "\",\"resourceId\":\""
                                        + stub.resourceId()
                                        + "\",\"versionId\":\""
                                        + stub.versionId()
                                        + "\",\"dueAt\":\""
                                        + OffsetDateTime.now(ZoneOffset.UTC).plusDays(7)
                                        + "\",\"attemptLimit\":1}");
                assertEquals("SUCCEEDED", assigned.get("state").stringValue());
                activityId =
                        UUID.fromString(
                                assigned.hasNonNull("targetId")
                                        ? assigned.get("targetId").stringValue()
                                        : assigned.get("id").stringValue());
            }
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM sample_activity WHERE id = ? AND workspace_id = ?",
                            Integer.class,
                            activityId,
                            personal),
                    "Giao bài personal phải ghi đúng hoạt động trong personal workspace");

            try (var student = openSession(studentLogin)) {
                JsonNode submitted =
                        sampleActionExpectCreated(
                                student,
                                personal,
                                "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                        + activityId
                                        + "\",\"contentStub\":\"Bài làm cá nhân\"}");
                assertEquals("SUCCEEDED", submitted.get("state").stringValue());
            }
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM sample_submission WHERE activity_id = ?"
                                    + " AND student_user_id = ?",
                            Integer.class,
                            activityId,
                            studentId),
                    "Bài nộp phải gắn với học sinh đã ghi danh");

            try (var outsider = openSession(outsiderLogin)) {
                MockHttpServletResponse blocked =
                        sampleAction(
                                outsider,
                                personal,
                                "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                        + activityId
                                        + "\",\"contentStub\":\"Bài của người ngoài\"}",
                                403);
                assertEquals(
                        403,
                        blocked.getStatus(),
                        "Chưa ghi danh vào lớp personal thì không nộp bài được");
            }
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM sample_submission WHERE activity_id = ?"
                                    + " AND student_user_id = ?",
                            Integer.class,
                            activityId,
                            outsiderId),
                    "Bài nộp của người chưa ghi danh không được ghi");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(studentLogin.subject());
            deleteUser(outsiderLogin.subject());
        }
    }

    @Test
    void ownerGradesAndPublishesAndStudentReadsResultInPersonalClass() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("personal-grade-owner");
        KeycloakLogin studentLogin = createVerifiedUser("personal-grade-student");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID studentId =
                    seedAccount(
                            studentLogin.subject(), "SELF_REGISTERED", studentLogin.email(), true);
            UUID personal = personalWorkspaceOf(ownerId);
            seedSubscription(personal, -1, 365, ownerId);
            PersonalChain chain = seedPersonalChain(personal, studentId);
            UUID resultId = seedSubmissionAndDraftResult(personal, ownerId, studentId, chain);

            try (var student = openSession(studentLogin)) {
                JsonNode before = getJson(student, resultsUri(personal, studentId), 200);
                assertEquals(
                        0,
                        before.get("items").size(),
                        "Kết quả DRAFT chưa công bố thì học sinh chưa thấy");
            }

            try (var owner = openSession(ownerLogin)) {
                JsonNode graded =
                        sampleActionExpectCreated(
                                owner,
                                personal,
                                "{\"action\":\"GRADE_RESULT\",\"resultId\":\""
                                        + resultId
                                        + "\",\"scoreStub\":\"9.5\",\"commentStub\":\"Làm tốt\"}");
                assertEquals("SUCCEEDED", graded.get("state").stringValue());
                JsonNode published =
                        sampleActionExpectCreated(
                                owner,
                                personal,
                                "{\"action\":\"PUBLISH_RESULT\",\"resultId\":\""
                                        + resultId
                                        + "\"}");
                assertEquals("SUCCEEDED", published.get("state").stringValue());
            }
            assertEquals(
                    "PUBLISHED",
                    jdbc.queryForObject(
                            "SELECT state FROM sample_result WHERE id = ?", String.class, resultId),
                    "Công bố phải chuyển kết quả sang PUBLISHED");
            assertEquals(
                    "9.5",
                    jdbc.queryForObject(
                            "SELECT score_stub FROM sample_result WHERE id = ?",
                            String.class,
                            resultId),
                    "Chấm phải ghi điểm của owner lớp personal");

            try (var student = openSession(studentLogin)) {
                JsonNode after = getJson(student, resultsUri(personal, studentId), 200);
                assertEquals(
                        1,
                        after.get("items").size(),
                        "Sau công bố, học sinh đọc được kết quả của mình tại lớp personal");
                assertEquals(
                        resultId.toString(), after.get("items").get(0).get("id").stringValue());
                assertEquals("9.5", after.get("items").get(0).get("score").stringValue());
            }
            try (var owner = openSession(ownerLogin)) {
                JsonNode ownerView = getJson(owner, resultsUri(personal, studentId), 200);
                assertEquals(
                        1,
                        ownerView.get("items").size(),
                        "Owner lớp personal đọc được kết quả đã công bố của người học");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(studentLogin.subject());
        }
    }

    @Test
    void leavingCenterKeepsPersonalRights() throws Exception {
        KeycloakLogin centerOwnerLogin = createVerifiedUser("us6-leave-center-owner");
        KeycloakLogin userLogin = createVerifiedUser("us6-leave-user");
        try {
            UUID centerOwnerId =
                    seedAccount(
                            centerOwnerLogin.subject(),
                            "SELF_REGISTERED",
                            centerOwnerLogin.email(),
                            true);
            UUID userId =
                    seedAccount(userLogin.subject(), "SELF_REGISTERED", userLogin.email(), true);
            UUID center = seedCenter(centerOwnerId);
            UUID membership = addMembership(center, userId, "TEACHER");
            seedSubscription(center, -1, 365, centerOwnerId);
            UUID personal = personalWorkspaceOf(userId);
            seedSubscription(personal, -1, 365, userId);

            UUID courseSubjectId;
            List<UUID> subjects;
            try (var centerOwner = openSession(centerOwnerLogin)) {
                UUID classId = createClass(centerOwner, center, "Lớp center US6.5");
                subjects = subjectIds(centerOwner, 1);
                UUID courseId =
                        createCourse(centerOwner, center, classId, "Khóa center US6.5", subjects);
                courseSubjectId = courseSubjectId(courseId, subjects.getFirst());
                assignTeachers(centerOwner, center, courseSubjectId, List.of(userId));
            }

            try (var user = openSession(userLogin)) {
                JsonNode before =
                        sampleActionExpectCreated(
                                user,
                                center,
                                "{\"action\":\"CREATE_RESOURCE\",\"courseSubjectId\":\""
                                        + courseSubjectId
                                        + "\",\"subjectId\":\""
                                        + subjects.getFirst()
                                        + "\",\"kind\":\"EXAM\",\"label\":\"Đề trước khi rời\"}");
                assertEquals("SUCCEEDED", before.get("state").stringValue());
            }

            try (var centerOwner = openSession(centerOwnerLogin)) {
                UUID operationId =
                        postMutation(
                                centerOwner,
                                "/api/workspaces/" + center + "/memberships/" + membership,
                                "{}",
                                "DELETE");
                awaitOperationSucceeded(jdbc, operationId);
            }

            try (var user = openSession(userLogin)) {
                MockHttpServletResponse atCenter =
                        sampleAction(
                                user,
                                center,
                                "{\"action\":\"CREATE_RESOURCE\",\"courseSubjectId\":\""
                                        + courseSubjectId
                                        + "\",\"subjectId\":\""
                                        + subjects.getFirst()
                                        + "\",\"kind\":\"EXAM\",\"label\":\"Đề sau khi rời\"}",
                                403);
                assertEquals(
                        403,
                        atCenter.getStatus(),
                        "Rời center thì quyền biên soạn theo phân công tại center chấm dứt");
                getJson(user, "/api/workspaces/" + center + "/classes", 403);

                JsonNode personalResource =
                        sampleActionExpectCreated(user, personal, createResourceJson());
                assertEquals("SUCCEEDED", personalResource.get("state").stringValue());
                UUID classId = createClass(user, personal, "Lớp cá nhân sau khi rời center");
                assertNotNull(classId, "Quyền cá nhân còn hiệu lực sau khi rời center");
            }
        } finally {
            deleteUser(centerOwnerLogin.subject());
            deleteUser(userLogin.subject());
        }
    }

    // ---------- Helper riêng của nhánh personal ----------

    private UUID personalWorkspaceOf(UUID userId) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId),
                "personal workspace");
    }

    private static String resultsUri(UUID workspaceId, UUID studentUserId) {
        return "/api/workspaces/" + workspaceId + "/students/" + studentUserId + "/results";
    }

    private static String createResourceJson() {
        return "{\"action\":\"CREATE_RESOURCE\",\"subjectId\":\""
                + UUID.randomUUID()
                + "\",\"kind\":\"EXAM\",\"label\":\"Học liệu cá nhân\"}";
    }

    private MockHttpServletResponse postRaw(RealSession session, String uri, String json)
            throws Exception {
        return mockMvc()
                .perform(
                        post(uri)
                                .cookie(session.cookie())
                                .with(csrf())
                                .header("Idempotency-Key", "personal-class-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse sampleAction(
            RealSession session, UUID workspaceId, String json, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response =
                postRaw(session, "/api/workspaces/" + workspaceId + "/samples/actions", json);
        assertEquals(
                expectedStatus,
                response.getStatus(),
                "Sample action tại workspace "
                        + workspaceId
                        + ", body: "
                        + response.getContentAsString());
        return response;
    }

    private JsonNode sampleActionExpectCreated(RealSession session, UUID workspaceId, String json)
            throws Exception {
        MockHttpServletResponse response = sampleAction(session, workspaceId, json, 201);
        return JSON.readTree(response.getContentAsString());
    }

    private record PersonalChain(
            UUID classId, UUID courseSubjectId, UUID classStudentId, UUID subjectId) {}

    private record ResourceStub(UUID resourceId, UUID versionId) {}

    /**
     * Seed thẳng chuỗi lớp/khóa/môn/roster/ghi danh trong personal workspace bằng JDBC: các test
     * action mẫu phải độc lập với route giảng dạy (đường route có test riêng ở trên), đúng mẫu seed
     * của các test kết quả mẫu. Mọi quan hệ ở trạng thái ACTIVE như sau một operation SUCCEEDED.
     */
    private PersonalChain seedPersonalChain(UUID workspaceId, UUID studentId) {
        UUID subjectId =
                java.util.Objects.requireNonNull(
                        jdbc.queryForObject("SELECT id FROM subject LIMIT 1", UUID.class),
                        "Danh mục môn phải có sẵn");
        UUID classId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID courseSubjectId = UUID.randomUUID();
        UUID classStudentId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO school_class(id,workspace_id,name,state)"
                            + " VALUES (?,?,?,'ACTIVE')",
                    classId,
                    workspaceId,
                    "Lớp personal seed");
            jdbc.update(
                    "INSERT INTO course(id,workspace_id,class_id,name,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    courseId,
                    workspaceId,
                    classId,
                    "Khóa personal seed");
            jdbc.update(
                    "INSERT INTO course_subject(id,workspace_id,class_id,course_id,subject_id,"
                            + "state) VALUES (?,?,?,?,?,'ACTIVE')",
                    courseSubjectId,
                    workspaceId,
                    classId,
                    courseId,
                    subjectId);
            jdbc.update(
                    "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    classStudentId,
                    workspaceId,
                    classId,
                    studentId);
            jdbc.update(
                    "INSERT INTO enrollment(id,workspace_id,class_student_id,course_subject_id,"
                            + "state) VALUES (?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(),
                    workspaceId,
                    classStudentId,
                    courseSubjectId);
            return null;
        });
        return new PersonalChain(classId, courseSubjectId, classStudentId, subjectId);
    }

    private ResourceStub seedResourceStub(UUID workspaceId, UUID createdBy, UUID subjectId) {
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    workspaceId,
                    createdBy,
                    subjectId);
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản mẫu','Nội dung','Đáp án','APPROVED')",
                    versionId,
                    resourceId);
            return null;
        });
        return new ResourceStub(resourceId, versionId);
    }

    /** Seed hoạt động, bài nộp và một kết quả DRAFT chưa có điểm; trả về id của sample_result. */
    private UUID seedSubmissionAndDraftResult(
            UUID workspaceId, UUID ownerId, UUID studentId, PersonalChain chain) {
        ResourceStub stub = seedResourceStub(workspaceId, ownerId, chain.subjectId());
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID periodId =
                java.util.Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subscription_period WHERE workspace_id = ?",
                                UUID.class,
                                workspaceId),
                        "Personal workspace phải có kỳ gói đã seed");
        tx(() -> {
            jdbc.update(
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,resource_id,"
                            + "version_id,assigned_by,assigned_at,due_at,attempt_limit,"
                            + "subscription_period_id,state)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ASSIGNED')",
                    activityId,
                    workspaceId,
                    chain.courseSubjectId(),
                    stub.resourceId(),
                    stub.versionId(),
                    ownerId,
                    now,
                    now.plusDays(7),
                    1,
                    periodId);
            jdbc.update(
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,student_user_id,"
                            + "attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,?,'SUBMITTED')",
                    submissionId,
                    workspaceId,
                    activityId,
                    studentId,
                    now,
                    "Bài làm mẫu");
            jdbc.update(
                    "INSERT INTO sample_result(id,workspace_id,submission_id,student_user_id,"
                            + "course_subject_id,score_stub,comment_stub,state,published_at,"
                            + "graded_by) VALUES (?,?,?,?,?,NULL,NULL,'DRAFT',NULL,NULL)",
                    resultId,
                    workspaceId,
                    submissionId,
                    studentId,
                    chain.courseSubjectId());
            return null;
        });
        return resultId;
    }
}
