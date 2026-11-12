package com.giaokhoa.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.verification.model.SampleActivity;
import com.giaokhoa.verification.model.SampleResult;
import com.giaokhoa.verification.model.SampleSubmission;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kiểm chứng ba entity mẫu (hoạt động, bài nộp, kết quả) round-trip qua JPA trên PostgreSQL thật,
 * theo mẫu ResourcePrimitivesSchemaIT: mapping cột/Instant/nullable khớp migration và các ràng buộc
 * DB chính (unique lượt nộp, CHECK công bố) bắt được cả khi ghi qua entity. Phần RLS/FK/trigger qua
 * role runtime nằm ở SampleRawConstraintsIT.
 */
// Slice JPA của Boot 4 trên PostgreSQL thật của stack TEST: replace = NONE giữ nguyên
// datasource đã khai (không thay bằng embedded); Flyway không thuộc slice nên phải import
// tường minh để migration chạy trước khi Hibernate validate schema. Slice không nạp
// worker/scheduler và chạy trên schema dùng chung: các slice cùng cấu hình dùng chung một
// context đã cache của Spring, mỗi test chạy trong giao dịch riêng và rollback khi kết thúc
// nên dữ kiện không lẫn sang class khác.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class SamplePrimitivesSchemaIT extends IntegrationEnvironment {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void entitiesRoundTripSamplePrimitives() {
        SeedGraph graph = seedGraph();
        Instant assignedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant dueAt = Instant.now().plus(7, ChronoUnit.DAYS);
        SampleActivity activity =
                new SampleActivity(
                        UUID.randomUUID(),
                        graph.workspaceId(),
                        graph.courseSubjectId(),
                        graph.resourceId(),
                        graph.versionId(),
                        graph.teacherId(),
                        assignedAt,
                        dueAt,
                        2,
                        graph.periodId(),
                        "ASSIGNED",
                        SampleActivity.CompletionPolicy.INTERRUPTIBLE);
        entityManager.persist(activity);
        entityManager.flush();
        assertEquals(
                "INTERRUPTIBLE",
                jdbc.queryForObject(
                        "SELECT completion_policy FROM sample_activity WHERE id = ?",
                        String.class,
                        activity.getId()),
                "Migration mới phải lưu rõ policy lịch sử mặc định trong PostgreSQL");
        SampleSubmission submission =
                new SampleSubmission(
                        UUID.randomUUID(),
                        graph.workspaceId(),
                        activity.getId(),
                        graph.studentId(),
                        1,
                        Instant.now(),
                        "Bài làm mẫu",
                        "SUBMITTED");
        entityManager.persist(submission);
        SampleResult result =
                new SampleResult(
                        UUID.randomUUID(),
                        graph.workspaceId(),
                        submission.getId(),
                        graph.studentId(),
                        graph.courseSubjectId(),
                        "8",
                        "Nhận xét mẫu",
                        "DRAFT",
                        null,
                        graph.teacherId());
        entityManager.persist(result);
        entityManager.flush();
        entityManager.clear();

        SampleActivity reloadedActivity =
                entityManager.find(SampleActivity.class, activity.getId());
        assertThat(reloadedActivity.getCourseSubjectId()).isEqualTo(graph.courseSubjectId());
        assertThat(reloadedActivity.getVersionId()).isEqualTo(graph.versionId());
        assertThat(reloadedActivity.getAttemptLimit()).isEqualTo(2);
        assertThat(reloadedActivity.getSubscriptionPeriodId()).isEqualTo(graph.periodId());
        assertThat(reloadedActivity.getState()).isEqualTo("ASSIGNED");
        assertThat(reloadedActivity.getCreatedAt()).isNotNull();
        SampleSubmission reloadedSubmission =
                entityManager.find(SampleSubmission.class, submission.getId());
        assertThat(reloadedSubmission.getActivityId()).isEqualTo(activity.getId());
        assertThat(reloadedSubmission.getAttemptNo()).isEqualTo(1);
        assertThat(reloadedSubmission.getContentStub()).isEqualTo("Bài làm mẫu");
        SampleResult reloadedResult = entityManager.find(SampleResult.class, result.getId());
        assertThat(reloadedResult.getSubmissionId()).isEqualTo(submission.getId());
        assertThat(reloadedResult.getStudentUserId()).isEqualTo(graph.studentId());
        assertThat(reloadedResult.getScoreStub()).isEqualTo("8");
        assertThat(reloadedResult.getPublishedAt()).isNull();
        assertThat(reloadedResult.getGradedBy()).isEqualTo(graph.teacherId());
    }

    @Test
    void rejectsDuplicateAttemptForSameStudent() {
        SeedGraph graph = seedGraph();
        UUID activityId = seedActivity(graph);
        seedSubmission(graph, activityId, 1);
        jdbc.update("SAVEPOINT duplicate_attempt");
        assertThatThrownBy(() -> seedSubmission(graph, activityId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT duplicate_attempt");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM sample_submission WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(1);
    }

    @Test
    void rejectsPublishedResultWithoutPublishedAt() {
        SeedGraph graph = seedGraph();
        UUID activityId = seedActivity(graph);
        UUID submissionId = seedSubmission(graph, activityId, 1);
        jdbc.update("SAVEPOINT published_without_timestamp");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "INSERT INTO sample_result (id, workspace_id,"
                                        + " submission_id, student_user_id,"
                                        + " course_subject_id, state, published_at)"
                                        + " VALUES (?, ?, ?, ?, ?, 'PUBLISHED', NULL)",
                                UUID.randomUUID(),
                                graph.workspaceId(),
                                submissionId,
                                graph.studentId(),
                                graph.courseSubjectId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT published_without_timestamp");
    }

    private record SeedGraph(
            UUID workspaceId,
            UUID courseSubjectId,
            UUID resourceId,
            UUID versionId,
            UUID periodId,
            UUID teacherId,
            UUID studentId) {}

    private SeedGraph seedGraph() {
        UUID teacherId = user();
        UUID studentId = user();
        UUID workspaceId = personalWorkspaceOf(teacherId);
        UUID subjectId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subject WHERE code = 'M1'", UUID.class));
        UUID classId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO school_class (id, workspace_id, name, state)"
                        + " VALUES (?, ?, 'Lớp mẫu', 'ACTIVE')",
                classId,
                workspaceId);
        UUID courseId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO course (id, workspace_id, class_id, name, state)"
                        + " VALUES (?, ?, ?, 'Khóa mẫu', 'ACTIVE')",
                courseId,
                workspaceId,
                classId);
        UUID courseSubjectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO course_subject (id, workspace_id, class_id, course_id, subject_id,"
                        + " state) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                courseSubjectId,
                workspaceId,
                classId,
                courseId,
                subjectId);
        UUID classStudentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO class_student (id, workspace_id, class_id, student_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                classStudentId,
                workspaceId,
                classId,
                studentId);
        jdbc.update(
                "INSERT INTO enrollment (id, workspace_id, class_student_id, course_subject_id,"
                        + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(),
                workspaceId,
                classStudentId,
                courseSubjectId);
        UUID resourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource (id, owner_workspace_id, created_by_user_id, kind,"
                        + " subject_id, state) VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                resourceId,
                workspaceId,
                teacherId,
                subjectId);
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource_version (id, resource_id, version_no, label, body_stub,"
                        + " answer_stub, review_state) VALUES (?, ?, 1, 'Bản 1', 'Nội dung',"
                        + " 'Đáp án', 'APPROVED')",
                versionId,
                resourceId);
        UUID periodId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscription_period (id, workspace_id, valid_from, expires_at,"
                        + " confirmation_ref, confirmed_by, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, now())",
                periodId,
                workspaceId,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                "ref-" + periodId,
                teacherId);
        return new SeedGraph(
                workspaceId,
                courseSubjectId,
                resourceId,
                versionId,
                periodId,
                teacherId,
                studentId);
    }

    private UUID seedActivity(SeedGraph graph) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sample_activity (id, workspace_id, course_subject_id, resource_id,"
                        + " version_id, assigned_by, assigned_at, due_at, attempt_limit,"
                        + " subscription_period_id, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 2, ?,"
                        + " 'ASSIGNED')",
                id,
                graph.workspaceId(),
                graph.courseSubjectId(),
                graph.resourceId(),
                graph.versionId(),
                graph.teacherId(),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)),
                graph.periodId());
        return id;
    }

    private UUID seedSubmission(SeedGraph graph, UUID activityId, int attemptNo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sample_submission (id, workspace_id, activity_id, student_user_id,"
                        + " attempt_no, submitted_at, content_stub, state)"
                        + " VALUES (?, ?, ?, ?, ?, now(), 'Bài làm mẫu', 'SUBMITTED')",
                id,
                graph.workspaceId(),
                activityId,
                graph.studentId(),
                attemptNo);
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account"
                        + " (id, keycloak_sub, username, email_verified, origin, identity_state)"
                        + " VALUES (?, ?, ?, false, 'SELF_REGISTERED', 'PENDING')",
                id,
                "sub-" + id,
                "user-" + id);
        jdbc.update(
                "INSERT INTO workspace (id, kind, owner_user_id, state)"
                        + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                UUID.randomUUID(),
                id);
        return id;
    }

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId));
    }
}
