package com.giaokhoa.shared.http;

/**
 * Registry trung tâm của toàn bộ mã lỗi backend: mọi member {@code code} trong đáp án ProblemDetail
 * (RFC 9457) đều lấy từ đây thay vì viết literal tại điểm ném, để từ vựng mã có một nguồn sự thật
 * duy nhất và không còn định nghĩa trùng giữa các class. Tên hằng giữ nguyên giá trị chuỗi của mã
 * để hợp đồng với client không đổi.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ACTIVITY_NOT_OPEN = "ACTIVITY_NOT_OPEN";
    public static final String ASSIGNMENT_NOT_FOUND = "ASSIGNMENT_NOT_FOUND";
    public static final String ATTEMPT_LIMIT_EXCEEDED = "ATTEMPT_LIMIT_EXCEEDED";
    public static final String AUTHORIZATION_NOT_READY = "AUTHORIZATION_NOT_READY";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String CANNOT_REMOVE_LAST_OWNER = "CANNOT_REMOVE_LAST_OWNER";
    public static final String CLASS_ALREADY_REVOKED = "CLASS_ALREADY_REVOKED";
    public static final String CLASS_NOT_ACTIVE = "CLASS_NOT_ACTIVE";
    public static final String CLASS_NOT_FOUND = "CLASS_NOT_FOUND";
    public static final String COURSE_ALREADY_REVOKED = "COURSE_ALREADY_REVOKED";
    public static final String COURSE_NOT_ACTIVE = "COURSE_NOT_ACTIVE";
    public static final String COURSE_NOT_FOUND = "COURSE_NOT_FOUND";
    public static final String COURSE_REQUIRES_SUBJECTS = "COURSE_REQUIRES_SUBJECTS";
    public static final String COURSE_SUBJECT_ALREADY_REVOKED = "COURSE_SUBJECT_ALREADY_REVOKED";
    public static final String COURSE_SUBJECT_NOT_ACTIVE = "COURSE_SUBJECT_NOT_ACTIVE";
    public static final String COURSE_SUBJECT_NOT_CURRENT = "COURSE_SUBJECT_NOT_CURRENT";
    public static final String COURSE_SUBJECT_NOT_FOUND = "COURSE_SUBJECT_NOT_FOUND";
    public static final String DATA_CONFLICT = "DATA_CONFLICT";
    public static final String DECISION_UNVERIFIABLE = "DECISION_UNVERIFIABLE";
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    public static final String ENROLLMENT_ALREADY_REVOKED = "ENROLLMENT_ALREADY_REVOKED";
    public static final String ENROLLMENT_BATCH_REQUIRED = "ENROLLMENT_BATCH_REQUIRED";
    public static final String ENROLLMENT_CLASS_MISMATCH = "ENROLLMENT_CLASS_MISMATCH";
    public static final String ENROLLMENT_NOT_FOUND = "ENROLLMENT_NOT_FOUND";
    public static final String ENROLLMENT_TARGET_NOT_FOUND = "ENROLLMENT_TARGET_NOT_FOUND";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String GUARDIAN_DECISION_INVALID = "GUARDIAN_DECISION_INVALID";
    public static final String GUARDIAN_LINK_ALREADY_CLOSED = "GUARDIAN_LINK_ALREADY_CLOSED";
    public static final String GUARDIAN_LINK_ALREADY_CURRENT = "GUARDIAN_LINK_ALREADY_CURRENT";
    public static final String GUARDIAN_LINK_ALREADY_DECIDED = "GUARDIAN_LINK_ALREADY_DECIDED";
    public static final String GUARDIAN_LINK_ALREADY_REVOKED = "GUARDIAN_LINK_ALREADY_REVOKED";
    public static final String GUARDIAN_LINK_NOT_APPROVED = "GUARDIAN_LINK_NOT_APPROVED";
    public static final String GUARDIAN_LINK_NOT_FOUND = "GUARDIAN_LINK_NOT_FOUND";
    public static final String GUARDIAN_PARENT_NOT_CURRENT = "GUARDIAN_PARENT_NOT_CURRENT";
    public static final String GUARDIAN_PREREQUISITE_NOT_CURRENT =
            "GUARDIAN_PREREQUISITE_NOT_CURRENT";
    public static final String GUARDIAN_STUDENT_NOT_FOUND = "GUARDIAN_STUDENT_NOT_FOUND";
    public static final String HOMEROOM_ASSIGNMENT_ALREADY_REVOKED =
            "HOMEROOM_ASSIGNMENT_ALREADY_REVOKED";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String INVALID_CENTER_NAME = "INVALID_CENTER_NAME";
    public static final String INVALID_CLASS_NAME = "INVALID_CLASS_NAME";
    public static final String INVALID_CONFIRMATION_REF = "INVALID_CONFIRMATION_REF";
    public static final String INVALID_COURSE_NAME = "INVALID_COURSE_NAME";
    public static final String INVALID_CURSOR = "INVALID_CURSOR";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String INVALID_IF_MATCH = "INVALID_IF_MATCH";
    public static final String INVALID_INVITATION_ROLES = "INVALID_INVITATION_ROLES";
    public static final String INVALID_INVITATION_TARGET = "INVALID_INVITATION_TARGET";
    public static final String INVALID_REASON = "INVALID_REASON";
    public static final String INVALID_SAMPLE_ACTION = "INVALID_SAMPLE_ACTION";
    public static final String INVALID_SUBSCRIPTION_PERIOD = "INVALID_SUBSCRIPTION_PERIOD";
    public static final String INVALID_TARGET = "INVALID_TARGET";
    public static final String INVALID_TARGET_EMAIL = "INVALID_TARGET_EMAIL";
    public static final String INVALID_VERIFICATION_NOTE = "INVALID_VERIFICATION_NOTE";
    public static final String INVITATION_EXPIRED = "INVITATION_EXPIRED";
    public static final String INVITATION_NOT_FOUND = "INVITATION_NOT_FOUND";
    public static final String INVITATION_NOT_PENDING = "INVITATION_NOT_PENDING";
    public static final String INVITATION_TARGET_MISMATCH = "INVITATION_TARGET_MISMATCH";
    public static final String INVITER_NOT_AUTHORIZED = "INVITER_NOT_AUTHORIZED";
    public static final String MANAGER_REQUIRES_OWNER = "MANAGER_REQUIRES_OWNER";
    public static final String MEMBERSHIP_ALREADY_REVOKED = "MEMBERSHIP_ALREADY_REVOKED";
    public static final String MEMBERSHIP_NOT_ACTIVE = "MEMBERSHIP_NOT_ACTIVE";
    public static final String MEMBERSHIP_NOT_FOUND = "MEMBERSHIP_NOT_FOUND";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String OPERATION_FAILED = "OPERATION_FAILED";
    public static final String OPERATOR_NOT_AUTHORIZED = "OPERATOR_NOT_AUTHORIZED";
    public static final String OWNER_NOT_INVITABLE = "OWNER_NOT_INVITABLE";
    public static final String OWNER_ROLE_NOT_ASSIGNABLE = "OWNER_ROLE_NOT_ASSIGNABLE";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String PRECONDITION_REQUIRED = "PRECONDITION_REQUIRED";
    public static final String RECOVERY_INVALID_STATE = "RECOVERY_INVALID_STATE";
    public static final String RECOVERY_REQUEST_NOT_FOUND = "RECOVERY_REQUEST_NOT_FOUND";
    public static final String RECOVERY_TARGET_NOT_FOUND = "RECOVERY_TARGET_NOT_FOUND";
    public static final String RESULT_NOT_DRAFT = "RESULT_NOT_DRAFT";
    public static final String ROSTER_ENTRY_ALREADY_REVOKED = "ROSTER_ENTRY_ALREADY_REVOKED";
    public static final String ROSTER_ENTRY_NOT_CURRENT = "ROSTER_ENTRY_NOT_CURRENT";
    public static final String ROSTER_ENTRY_NOT_FOUND = "ROSTER_ENTRY_NOT_FOUND";
    public static final String ROSTER_STUDENTS_REQUIRED = "ROSTER_STUDENTS_REQUIRED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String STUDENT_ACCOUNT_NOT_FOUND = "STUDENT_ACCOUNT_NOT_FOUND";
    public static final String STUDENT_NOT_CENTER_MEMBER = "STUDENT_NOT_CENTER_MEMBER";
    public static final String STUDENT_NOT_CENTER_STUDENT = "STUDENT_NOT_CENTER_STUDENT";
    public static final String SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    public static final String SUBSCRIPTION_CONFIRMATION_REF_USED =
            "SUBSCRIPTION_CONFIRMATION_REF_USED";
    public static final String SUBSCRIPTION_INACTIVE = "SUBSCRIPTION_INACTIVE";
    public static final String SUBSCRIPTION_WORKSPACE_KIND = "SUBSCRIPTION_WORKSPACE_KIND";
    public static final String TEACHERS_REQUIRED = "TEACHERS_REQUIRED";
    public static final String TEACHER_NOT_CENTER_TEACHER = "TEACHER_NOT_CENTER_TEACHER";
    public static final String TEACHING_ASSIGNMENT_ALREADY_REVOKED =
            "TEACHING_ASSIGNMENT_ALREADY_REVOKED";
    public static final String TEACHING_CONTEXT_NOT_ACTIVE = "TEACHING_CONTEXT_NOT_ACTIVE";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String UNSUPPORTED_SAMPLE_ACTION = "UNSUPPORTED_SAMPLE_ACTION";
    public static final String USERNAME_MISMATCH = "USERNAME_MISMATCH";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String VERIFICATION_EMAIL_COOLDOWN = "VERIFICATION_EMAIL_COOLDOWN";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND";
}
