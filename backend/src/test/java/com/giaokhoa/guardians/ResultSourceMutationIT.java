package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.teaching.AssignmentService;
import com.giaokhoa.teaching.EnrollmentService;
import com.giaokhoa.tenancy.CenterService;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Mutation của nguồn khác phải cập nhật bundle kết quả trong cùng operation, giữ nguồn độc lập. */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResultSourceMutationIT extends GuardianFixture {
    private static final String SCHEMA =
            "result_source_mutation_" + UUID.randomUUID().toString().replace("-", "");

    private enum SourceMutation {
        PARENT_ROLE,
        STUDENT_MEMBERSHIP,
        ENROLLMENT,
        TEACHER_ASSIGNMENT,
        CLASS_CASCADE
    }

    private record Scenario(UUID classId, UUID courseSubjectId, UUID resultId) {}

    @Autowired private CenterService centers;
    @Autowired private GuardianLinkService links;
    @Autowired private EnrollmentService enrollments;
    @Autowired private AssignmentService assignments;
    @Autowired private ResultPolicyProjector resultProjector;
    @Autowired private IdempotencyService idempotency;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @ParameterizedTest
    @EnumSource(SourceMutation.class)
    void sourceMutationReconcilesPublishedResultsAndPreservesIndependentRead(
            SourceMutation mutation) throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (RealSession owner = openSession(center.owner());
                RealSession parent = openSession(center.parent())) {
            Scenario scenario = prepareScenario(owner, center);
            assertResultVisible(parent, center, scenario.resultId());

            KeycloakLogin remainingReader =
                    switch (mutation) {
                        case PARENT_ROLE -> {
                            UUID membershipId = membershipIdOf(center, center.parentId());
                            Long version =
                                    jdbc.queryForObject(
                                            "SELECT version FROM center_membership WHERE id = ?",
                                            Long.class,
                                            membershipId);
                            await(
                                    centers.replaceRoles(
                                            center.ownerId(),
                                            center.workspaceId(),
                                            membershipId,
                                            Set.of(),
                                            "\"" + Objects.requireNonNull(version) + "\"",
                                            key(),
                                            hash()));
                            assertEquals("REVOKED", linkStateOf(center));
                            assertEquals(403, getRaw(parent, resultsPath(center)).getStatus());
                            yield center.students().getFirst();
                        }
                        case STUDENT_MEMBERSHIP -> {
                            await(
                                    centers.revokeMembership(
                                            center.ownerId(),
                                            center.workspaceId(),
                                            membershipIdOf(center, center.studentX()),
                                            key(),
                                            hash()));
                            assertEquals("REVOKED", linkStateOf(center));
                            assertEquals(403, getRaw(parent, resultsPath(center)).getStatus());
                            yield center.teacher();
                        }
                        case ENROLLMENT -> {
                            UUID enrollmentId =
                                    Objects.requireNonNull(
                                            jdbc.queryForObject(
                                                    "SELECT id FROM enrollment WHERE workspace_id = ?"
                                                            + " AND course_subject_id = ?",
                                                    UUID.class,
                                                    center.workspaceId(),
                                                    scenario.courseSubjectId()));
                            await(
                                    enrollments.revokeEnrollment(
                                            center.ownerId(),
                                            center.workspaceId(),
                                            enrollmentId,
                                            key(),
                                            hash()));
                            try (RealSession student = openSession(center.students().getFirst())) {
                                assertEquals(
                                        0,
                                        getJson(student, resultsPath(center), 200)
                                                .get("items")
                                                .size());
                            }
                            yield center.parent();
                        }
                        case TEACHER_ASSIGNMENT -> {
                            UUID assignmentId =
                                    Objects.requireNonNull(
                                            jdbc.queryForObject(
                                                    "SELECT id FROM teaching_assignment"
                                                            + " WHERE workspace_id = ?"
                                                            + " AND course_subject_id = ?",
                                                    UUID.class,
                                                    center.workspaceId(),
                                                    scenario.courseSubjectId()));
                            await(
                                    assignments.revokeTeacherAssignment(
                                            center.ownerId(),
                                            center.workspaceId(),
                                            scenario.courseSubjectId(),
                                            assignmentId,
                                            key(),
                                            hash()));
                            try (RealSession teacher = openSession(center.teacher())) {
                                assertEquals(403, getRaw(teacher, resultsPath(center)).getStatus());
                            }
                            yield center.parent();
                        }
                        case CLASS_CASCADE -> {
                            String path =
                                    "/api/workspaces/"
                                            + center.workspaceId()
                                            + "/classes/"
                                            + scenario.classId();
                            UUID operationId =
                                    operationIdOf(mutate(owner, delete(path), null), "Thu hồi lớp");
                            awaitOperationSucceeded(jdbc, operationId);
                            assertEquals(
                                    "REVOKED",
                                    jdbc.queryForObject(
                                            "SELECT state FROM enrollment WHERE workspace_id = ?"
                                                    + " AND course_subject_id = ?",
                                            String.class,
                                            center.workspaceId(),
                                            scenario.courseSubjectId()));
                            assertEquals(
                                    "REVOKED",
                                    jdbc.queryForObject(
                                            "SELECT state FROM teaching_assignment WHERE workspace_id = ?"
                                                    + " AND course_subject_id = ?",
                                            String.class,
                                            center.workspaceId(),
                                            scenario.courseSubjectId()));
                            yield center.parent();
                        }
                    };
            if (remainingReader.equals(center.parent())) {
                assertResultVisible(parent, center, scenario.resultId());
            } else {
                try (RealSession reader = openSession(remainingReader)) {
                    assertResultVisible(reader, center, scenario.resultId());
                }
            }
        }
    }

    private Scenario prepareScenario(RealSession owner, GuardianCenter center) throws Exception {
        UUID workspaceId = center.workspaceId();
        UUID classId = createClass(owner, workspaceId, "Lớp nguồn kết quả");
        SubjectRef subject = firstSubject(owner);
        UUID courseId =
                createCourse(
                        owner, workspaceId, classId, "Khóa nguồn kết quả", List.of(subject.id()));
        UUID courseSubjectId = courseSubjectId(courseId, subject.id());
        UUID rosterId = addRoster(owner, workspaceId, classId, center.studentX());
        enroll(owner, workspaceId, rosterId, courseSubjectId);
        assignTeacher(owner, workspaceId, courseSubjectId, center.teacherId());
        await(links.requestLink(center.parentId(), workspaceId, center.studentX(), key(), hash()));
        UUID linkId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM guardian_link WHERE workspace_id = ?"
                                        + " AND parent_user_id = ?",
                                UUID.class,
                                workspaceId,
                                center.parentId()));
        await(
                links.decideLink(
                        center.managerId(), workspaceId, linkId, "APPROVE", 0, key(), hash()));
        UUID resultId = seedPublishedResult(center, courseSubjectId, subject.id());
        await(
                resultProjector.recordResultChange(
                        center.ownerId(),
                        workspaceId,
                        key(),
                        hash(),
                        List.of(NativePolicyTemplates.resultKey(workspaceId, resultId))));
        return new Scenario(classId, courseSubjectId, resultId);
    }

    private UUID seedPublishedResult(GuardianCenter center, UUID courseSubjectId, UUID subjectId) {
        UUID workspaceId = center.workspaceId();
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID periodId =
                jdbc.queryForObject(
                        "SELECT id FROM subscription_period WHERE workspace_id = ?",
                        UUID.class,
                        workspaceId);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    workspaceId,
                    center.ownerId(),
                    subjectId);
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản mẫu','Nội dung','Đáp án','APPROVED')",
                    versionId,
                    resourceId);
            jdbc.update(
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,resource_id,"
                            + "version_id,assigned_by,assigned_at,due_at,attempt_limit,"
                            + "subscription_period_id,state) VALUES (?,?,?,?,?,?,?,?,1,?,'ASSIGNED')",
                    activityId,
                    workspaceId,
                    courseSubjectId,
                    resourceId,
                    versionId,
                    center.ownerId(),
                    now,
                    now.plusDays(7),
                    periodId);
            jdbc.update(
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,student_user_id,"
                            + "attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,'Bài mẫu','SUBMITTED')",
                    submissionId,
                    workspaceId,
                    activityId,
                    center.studentX(),
                    now);
            jdbc.update(
                    "INSERT INTO sample_result(id,workspace_id,submission_id,student_user_id,"
                            + "course_subject_id,state,published_at,graded_by)"
                            + " VALUES (?,?,?,?,?,'PUBLISHED',?,?)",
                    resultId,
                    workspaceId,
                    submissionId,
                    center.studentX(),
                    courseSubjectId,
                    now,
                    center.teacherId());
            return null;
        });
        return resultId;
    }

    private void assertResultVisible(RealSession reader, GuardianCenter center, UUID resultId)
            throws Exception {
        JsonNode items = getJson(reader, resultsPath(center), 200).get("items");
        assertEquals(1, items.size(), "Nguồn độc lập còn hiệu lực vẫn đọc được kết quả công bố");
        assertEquals(resultId.toString(), items.get(0).get("id").stringValue());
    }

    private UUID membershipIdOf(GuardianCenter center, UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM center_membership WHERE workspace_id = ? AND user_id = ?",
                        UUID.class,
                        center.workspaceId(),
                        userId));
    }

    private String linkStateOf(GuardianCenter center) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT state FROM guardian_link WHERE workspace_id = ? AND parent_user_id = ?",
                        String.class,
                        center.workspaceId(),
                        center.parentId()));
    }

    private static String resultsPath(GuardianCenter center) {
        return resultsPath(center.workspaceId(), center.studentX());
    }

    private void await(AuthorizationOperation operation) {
        awaitOperationSucceeded(jdbc, operation.getId());
    }

    private static String key() {
        return "result-source-" + UUID.randomUUID();
    }

    private String hash() {
        return idempotency.payloadHash(
                "POST", "/test/result-source-mutation", JSON.createObjectNode());
    }
}
