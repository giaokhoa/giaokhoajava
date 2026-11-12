package com.giaokhoa.shared.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.giaokhoa.authorization.decision.CapabilitiesController;
import com.giaokhoa.guardians.web.GuardianLinkController;
import com.giaokhoa.identity.web.RecoveryController;
import com.giaokhoa.subscriptions.web.SubscriptionController;
import com.giaokhoa.teaching.web.AssignmentController;
import com.giaokhoa.teaching.web.ClassController;
import com.giaokhoa.teaching.web.CourseController;
import com.giaokhoa.tenancy.web.CenterController;
import com.giaokhoa.tenancy.web.MembershipController;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Constraint Bean Validation trên các record DTO của request: chỉ mirror đúng các kiểm tra hình
 * thức mà controller/service đã áp dụng trước đây (thiếu field, chuỗi rỗng, độ dài tên), để request
 * đang được nhận không bị từ chối thêm ở tầng bind. Các điều kiện phụ thuộc dữ liệu (ví dụ email
 * của học sinh: vắng mặt thì được, có mặt thì không được rỗng) và dữ kiện quyết định quyền (scope
 * lạ) cố ý không đặt constraint.
 */
class RequestDtoValidationTest {
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violationsOf(Object bean) {
        return VALIDATOR.validate(bean).stream()
                .map(v ->
                        v.getPropertyPath()
                                + ":"
                                + v.getConstraintDescriptor()
                                        .getAnnotation()
                                        .annotationType()
                                        .getSimpleName())
                .collect(Collectors.toSet());
    }

    @Test
    void guardianRequestRequiresStudent() {
        assertEquals(
                Set.of("studentUserId:NotNull"),
                violationsOf(new GuardianLinkController.GuardianRequest(null)));
        assertEquals(
                Set.of(),
                violationsOf(new GuardianLinkController.GuardianRequest(UUID.randomUUID())));
    }

    @Test
    void guardianDecisionRequiresBothFields() {
        assertEquals(
                Set.of("decision:NotNull", "expectedVersion:NotNull"),
                violationsOf(new GuardianLinkController.GuardianDecision(null, null, null)));
    }

    @Test
    void recoverySubmitRequiresTargetAndReasonButWorkspaceStaysOptional() {
        assertEquals(
                Set.of("targetUserId:NotNull", "reason:NotBlank"),
                violationsOf(new RecoveryController.SubmitRequest(null, null, " ")));
        assertEquals(
                Set.of(),
                violationsOf(
                        new RecoveryController.SubmitRequest(
                                UUID.randomUUID(), null, "Quên mật khẩu")));
    }

    @Test
    void recoveryResolveRequiresDecisionOnly() {
        assertEquals(
                Set.of("decision:NotNull"),
                violationsOf(new RecoveryController.ResolveRequest(null, null)));
    }

    @Test
    void subscriptionConfirmRequiresPeriodFields() {
        assertEquals(
                Set.of("workspaceId:NotNull", "validFrom:NotNull", "expiresAt:NotNull"),
                violationsOf(
                        new SubscriptionController.SubscriptionConfirmRequest(
                                null, null, null, null)));
        assertEquals(
                Set.of(),
                violationsOf(
                        new SubscriptionController.SubscriptionConfirmRequest(
                                UUID.randomUUID(),
                                OffsetDateTime.now(),
                                OffsetDateTime.now().plusDays(30),
                                null)));
    }

    @Test
    void homeroomAssignRequiresTeacher() {
        assertEquals(
                Set.of("teacherUserId:NotNull"),
                violationsOf(new AssignmentController.HomeroomAssignRequest(null)));
    }

    @Test
    void centerCreateRequiresNameAndOwnerWithinNameLimit() {
        assertEquals(
                Set.of("name:NotBlank", "ownerUserId:NotNull"),
                violationsOf(new CenterController.CenterCreateRequest(" ", null)));
        assertEquals(
                Set.of("name:Size"),
                violationsOf(
                        new CenterController.CenterCreateRequest(
                                "x".repeat(256), UUID.randomUUID())));
    }

    @Test
    void studentIssueRequiresNamesButEmailStaysConditional() {
        assertEquals(
                Set.of("username:NotBlank", "displayName:NotBlank"),
                violationsOf(new MembershipController.StudentIssueRequest("", null, null)));
        // Email có mặt nhưng rỗng vẫn qua tầng DTO: điều kiện "vắng mặt thì được, có mặt thì
        // không rỗng" không biểu diễn được bằng constraint chuẩn nên controller giữ kiểm tra đó.
        assertEquals(
                Set.of(),
                violationsOf(
                        new MembershipController.StudentIssueRequest("hs01", "Học Sinh", " ")));
    }

    // Ca này cố ý truyền tên null để khóa vi phạm NotBlank: component name của
    // ClassController.NameRequest không @Nullable trong package @NullMarked nên NullAway biến
    // literal null thành lỗi biên dịch; miễn trừ chỉ trong phương thức này, ca test giữ nguyên.
    @SuppressWarnings("NullAway")
    @Test
    void classAndCourseNamesAreNotBlankWithinLimit() {
        assertEquals(Set.of("name:NotBlank"), violationsOf(new ClassController.NameRequest("")));
        assertEquals(Set.of("name:NotBlank"), violationsOf(new ClassController.NameRequest(null)));
        assertEquals(
                Set.of("name:Size"),
                violationsOf(new ClassController.NameRequest("x".repeat(256))));
        assertEquals(
                Set.of("name:NotBlank"),
                violationsOf(new CourseController.CourseCreateRequest("", List.of())));
        assertEquals(Set.of("name:NotBlank"), violationsOf(new CourseController.NameRequest("")));
    }

    @Test
    void capabilityTargetsCascadeButScopesAreDecisionData() {
        assertEquals(
                Set.of("targets[0].type:NotBlank"),
                violationsOf(
                        new CapabilitiesController.CapabilitiesRequest(
                                List.of(
                                        new CapabilitiesController.TargetInput(
                                                " ", null, null, List.of())))));
        // Scope không tồn tại trong catalog là dữ kiện để PDP quyết định, không phải lỗi dữ liệu.
        assertEquals(
                Set.of(),
                violationsOf(
                        new CapabilitiesController.CapabilitiesRequest(
                                List.of(
                                        new CapabilitiesController.TargetInput(
                                                "workspace", null, null, List.of("scope-la"))))));
    }
}
