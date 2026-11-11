package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.SubscriptionPeriod;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.UserAccountRef;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.WorkspaceInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Khóa phân loại gate gói của bundle giảng dạy cá nhân: scope sửa (update) của khóa phải gate theo
 * kỳ gói như scope sửa của lớp, còn scope đọc thì không.
 */
class PersonalTeachingPolicyProjectorTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID CLASS = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID PERIOD = UUID.randomUUID();

    private final SchoolClassRepository schoolClasses = mock(SchoolClassRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final TeachingScopePreconditions preconditions = mock(TeachingScopePreconditions.class);

    @Test
    @DisplayName("UPDATE của khóa gate theo kỳ gói như UPDATE của lớp, READ thì không gate")
    void courseUpdateIsPeriodGatedLikeClassUpdate() {
        PersonalTeachingPolicyProjector projector = projector();
        String courseTarget = NativePolicyTemplates.courseKey(WORKSPACE, COURSE);
        String classTarget = NativePolicyTemplates.classKey(WORKSPACE, CLASS);

        List<String> courseUpdate = projector.sourcesFor(courseTarget, "update");
        assertEquals(
                1,
                courseUpdate.size(),
                "UPDATE của khóa phải có đúng nguồn chủ sở hữu, body: " + courseUpdate);
        assertTrue(
                courseUpdate.getFirst().startsWith("PERSONAL_TEACHING_OWNER_PERIOD|"),
                "Nguồn UPDATE của khóa phải mang descriptor kỳ gói, body: " + courseUpdate);

        List<String> courseRead = projector.sourcesFor(courseTarget, "read");
        assertEquals(1, courseRead.size(), "READ của khóa phải có đúng nguồn chủ sở hữu");
        assertTrue(
                courseRead.getFirst().startsWith("PERSONAL_TEACHING_OWNER|")
                        && !courseRead.getFirst().startsWith("PERSONAL_TEACHING_OWNER_PERIOD|"),
                "READ của khóa không qua gate gói, body: " + courseRead);

        List<String> classUpdate = projector.sourcesFor(classTarget, "update");
        assertEquals(1, classUpdate.size(), "UPDATE của lớp phải có đúng nguồn chủ sở hữu");
        assertTrue(
                classUpdate.getFirst().startsWith("PERSONAL_TEACHING_OWNER_PERIOD|"),
                "UPDATE của lớp gate theo kỳ gói (đối chứng), body: " + classUpdate);
    }

    private PersonalTeachingPolicyProjector projector() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(preconditions.workspaceInfo(WORKSPACE))
                .thenReturn(Optional.of(new WorkspaceInfo(WORKSPACE, "PERSONAL", "ACTIVE", OWNER)));
        when(preconditions.personalOwnerAccount(WORKSPACE))
                .thenReturn(Optional.of(new UserAccountRef(OWNER, "sub-owner")));
        when(preconditions.subscriptionPeriods(WORKSPACE))
                .thenReturn(
                        List.of(
                                new SubscriptionPeriod(
                                        PERIOD,
                                        Instant.parse("2026-01-01T00:00:00Z"),
                                        Instant.parse("2027-01-01T00:00:00Z"))));
        when(schoolClasses.existsByWorkspaceIdAndId(WORKSPACE, CLASS)).thenReturn(true);
        when(courses.existsByWorkspaceIdAndId(WORKSPACE, COURSE)).thenReturn(true);
        return new PersonalTeachingPolicyProjector(
                schoolClasses,
                courses,
                mock(CourseSubjectRepository.class),
                mock(EnrollmentRepository.class),
                preconditions,
                mock(WorkspaceTransaction.class),
                transactions);
    }
}
