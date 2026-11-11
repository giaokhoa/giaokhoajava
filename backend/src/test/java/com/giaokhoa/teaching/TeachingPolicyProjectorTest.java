package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.SubscriptionPeriod;
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
 * Khóa phân loại gate gói của bundle giảng dạy center: scope sửa (update) của khóa phải gate theo
 * kỳ gói như scope sửa của lớp, còn scope đọc thì không.
 */
class TeachingPolicyProjectorTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID CLASS = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID PERIOD = UUID.randomUUID();

    private final SchoolClassRepository schoolClasses = mock(SchoolClassRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final TeachingScopePreconditions preconditions = mock(TeachingScopePreconditions.class);
    private final HomeroomAssignmentRepository homeroomAssignments =
            mock(HomeroomAssignmentRepository.class);

    @Test
    @DisplayName("UPDATE của khóa gate theo kỳ gói như UPDATE của lớp, READ thì không gate")
    void courseUpdateIsPeriodGatedLikeClassUpdate() {
        TeachingPolicyProjector projector = projector();
        String courseTarget = NativePolicyTemplates.courseKey(WORKSPACE, COURSE);
        String classTarget = NativePolicyTemplates.classKey(WORKSPACE, CLASS);

        List<String> courseUpdate = projector.sourcesFor(courseTarget, "update");
        assertEquals(
                2,
                courseUpdate.size(),
                "UPDATE của khóa phải có nguồn của owner và manager, body: " + courseUpdate);
        assertTrue(
                courseUpdate.stream().allMatch(d -> d.startsWith("TEACHING_GROUP_PERIOD|")),
                "Mọi nguồn UPDATE của khóa đều phải mang descriptor kỳ gói, body: " + courseUpdate);

        List<String> courseRead = projector.sourcesFor(courseTarget, "read");
        assertEquals(2, courseRead.size(), "READ của khóa phải có nguồn owner/manager");
        assertTrue(
                courseRead.stream().allMatch(d -> d.startsWith("TEACHING_GROUP|")),
                "READ của khóa không qua gate gói, body: " + courseRead);

        List<String> classUpdate = projector.sourcesFor(classTarget, "update");
        assertEquals(2, classUpdate.size(), "UPDATE của lớp phải có nguồn owner/manager");
        assertTrue(
                classUpdate.stream().allMatch(d -> d.startsWith("TEACHING_GROUP_PERIOD|")),
                "UPDATE của lớp gate theo kỳ gói (đối chứng), body: " + classUpdate);
    }

    private TeachingPolicyProjector projector() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(preconditions.workspaceInfo(WORKSPACE))
                .thenReturn(Optional.of(new WorkspaceInfo(WORKSPACE, "CENTER", "ACTIVE", null)));
        when(preconditions.existsActiveMemberWithRole(WORKSPACE, "OWNER")).thenReturn(true);
        when(preconditions.existsActiveMemberWithRole(WORKSPACE, "MANAGER")).thenReturn(true);
        when(preconditions.subscriptionPeriods(WORKSPACE))
                .thenReturn(
                        List.of(
                                new SubscriptionPeriod(
                                        PERIOD,
                                        Instant.parse("2026-01-01T00:00:00Z"),
                                        Instant.parse("2027-01-01T00:00:00Z"))));
        when(schoolClasses.existsByWorkspaceIdAndId(WORKSPACE, CLASS)).thenReturn(true);
        when(courses.findClassIdByWorkspaceIdAndId(WORKSPACE, COURSE))
                .thenReturn(Optional.of(CLASS));
        when(homeroomAssignments.existsByWorkspaceIdAndClassIdAndStateIn(any(), any(), any()))
                .thenReturn(false);
        return new TeachingPolicyProjector(
                schoolClasses,
                courses,
                mock(CourseSubjectRepository.class),
                homeroomAssignments,
                mock(TeachingAssignmentRepository.class),
                mock(EnrollmentRepository.class),
                preconditions,
                mock(AuthorizationProvisioningClient.class),
                mock(WorkspaceTransaction.class),
                transactions);
    }
}
