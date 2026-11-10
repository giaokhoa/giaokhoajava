package com.giaokhoa.authorization.policy;

import com.giaokhoa.authorization.permission.PermissionCatalog;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Các template native cố định của hợp đồng authorization: realm group path, target key cùng scope
 * cố định của chúng, và các tên Keycloak xác định suy ra từ chúng. Template do hợp đồng cố định,
 * không bao giờ do center hay caller chọn; prerequisite role là realm group, không phải global
 * realm role.
 */
public final class NativePolicyTemplates {
    public static final String ELIGIBILITY_GROUP = "/gk/identity/eligible";
    public static final String PLATFORM_OPERATORS_GROUP = "/gk/platform/operators";

    /** Tiền tố key của mọi target thuộc workspace; ngữ pháp key do template này định nghĩa. */
    public static final String WORKSPACE_PREFIX = "workspace:";

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}";
    private static final Pattern GROUP_SOURCE =
            Pattern.compile(
                    "/gk/(?:identity/eligible|platform/operators|workspaces/"
                            + UUID_PATTERN
                            + "/(?:members|owners|managers|roles/(?:teachers|students|parents)"
                            + "|classes/"
                            + UUID_PATTERN
                            + "/homeroom"
                            + "|course-subjects/"
                            + UUID_PATTERN
                            + "/(?:teachers|students)"
                            + "|students/"
                            + UUID_PATTERN
                            + "/guardians))");

    /** Keycloak hiển thị biên của time policy theo UTC bằng đúng pattern này. */
    public static final DateTimeFormatter KEYCLOAK_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private NativePolicyTemplates() {}

    /**
     * Các prerequisite operational role của một workspace; được chiếu thành group, không bao giờ là
     * global role.
     */
    public enum PrerequisiteRole {
        TEACHERS("teachers"),
        STUDENTS("students"),
        PARENTS("parents");

        private final String segment;

        PrerequisiteRole(String segment) {
            this.segment = segment;
        }
    }

    public static String membersGroup(UUID workspaceId) {
        return workspaceGroup(workspaceId, "members");
    }

    public static String ownersGroup(UUID workspaceId) {
        return workspaceGroup(workspaceId, "owners");
    }

    public static String managersGroup(UUID workspaceId) {
        return workspaceGroup(workspaceId, "managers");
    }

    public static String prerequisiteRoleGroup(UUID workspaceId, PrerequisiteRole role) {
        return workspaceGroup(workspaceId, "roles/" + role.segment);
    }

    public static String homeroomGroup(UUID workspaceId, UUID classId) {
        return workspaceGroup(workspaceId, "classes/" + requireId(classId) + "/homeroom");
    }

    public static String courseSubjectTeachersGroup(UUID workspaceId, UUID courseSubjectId) {
        return workspaceGroup(
                workspaceId, "course-subjects/" + requireId(courseSubjectId) + "/teachers");
    }

    public static String courseSubjectStudentsGroup(UUID workspaceId, UUID courseSubjectId) {
        return workspaceGroup(
                workspaceId, "course-subjects/" + requireId(courseSubjectId) + "/students");
    }

    public static String guardiansGroup(UUID workspaceId, UUID studentUserId) {
        return workspaceGroup(workspaceId, "students/" + requireId(studentUserId) + "/guardians");
    }

    private static String workspaceGroup(UUID workspaceId, String suffix) {
        return "/gk/workspaces/" + requireId(workspaceId) + "/" + suffix;
    }

    /** Chỉ true với các group source cố định của hợp đồng. */
    public static boolean isGroupSource(String path) {
        return path != null && GROUP_SOURCE.matcher(path).matches();
    }

    /** Các loại target của hợp đồng, mỗi loại với dạng key và tập scope cố định của nó. */
    public enum TargetType {
        // Danh sách scope của từng loại target không chép tại đây: chúng suy ra từ
        // PermissionCatalog, nguồn sự thật duy nhất của hợp đồng scope.
        PLATFORM("platform", PermissionCatalog.names(PermissionCatalog.PlatformScope.values())),
        PROFILE(
                "account:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ProfileScope.values())),
        WORKSPACE(
                WORKSPACE_PREFIX + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.WorkspaceScope.values())),
        CLASS(
                WORKSPACE_PREFIX + UUID_PATTERN + ":class:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ClassScope.values())),
        COURSE(
                WORKSPACE_PREFIX + UUID_PATTERN + ":course:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.CourseScope.values())),
        COURSE_SUBJECT(
                WORKSPACE_PREFIX + UUID_PATTERN + ":course-subject:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.CourseSubjectScope.values())),
        RESOURCE_VERSION(
                WORKSPACE_PREFIX
                        + UUID_PATTERN
                        + ":resource:"
                        + UUID_PATTERN
                        + ":version:"
                        + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ResourceVersionScope.values())),
        RESOURCE(
                WORKSPACE_PREFIX + UUID_PATTERN + ":resource:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ResourceScope.values())),
        ACTIVITY(
                WORKSPACE_PREFIX + UUID_PATTERN + ":activity:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ActivityScope.values())),
        RESULT(
                WORKSPACE_PREFIX + UUID_PATTERN + ":result:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.ResultScope.values())),
        GRANT(
                "grant:" + UUID_PATTERN,
                PermissionCatalog.names(PermissionCatalog.GrantScope.values()));

        private final Pattern keyPattern;
        private final List<String> fixedScopes;

        TargetType(String keyPattern, List<String> fixedScopes) {
            this.keyPattern = Pattern.compile(keyPattern);
            this.fixedScopes = List.copyOf(fixedScopes);
        }

        public List<String> scopes() {
            return fixedScopes;
        }

        boolean matches(String targetKey) {
            return targetKey != null && keyPattern.matcher(targetKey).matches();
        }
    }

    /** Loại target mà một key thuộc về; rỗng khi key không khớp template cố định nào. */
    public static Optional<TargetType> targetTypeOf(String targetKey) {
        for (TargetType type : TargetType.values()) {
            if (type.matches(targetKey)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Chỉ các endpoint profile/onboarding/recovery mới được bỏ qua eligibility gate nghiệp vụ. */
    public static boolean requiresEligibility(TargetType type, String scope) {
        return switch (type) {
            case PROFILE ->
                    !PermissionCatalog.ProfileScope.PROFILE_READ.scopeName().equals(scope)
                            && !PermissionCatalog.ProfileScope.RECOVERY_REQUEST
                                    .scopeName()
                                    .equals(scope);
            case PLATFORM ->
                    !PermissionCatalog.PlatformScope.ACCOUNT_RECOVER.scopeName().equals(scope);
            default -> true;
        };
    }

    public static String platformKey() {
        return "platform";
    }

    public static String profileKey(UUID userId) {
        return "account:" + requireId(userId);
    }

    public static String workspaceKey(UUID workspaceId) {
        return WORKSPACE_PREFIX + requireId(workspaceId);
    }

    public static String classKey(UUID workspaceId, UUID classId) {
        return workspaceKey(workspaceId) + ":class:" + requireId(classId);
    }

    public static String courseKey(UUID workspaceId, UUID courseId) {
        return workspaceKey(workspaceId) + ":course:" + requireId(courseId);
    }

    public static String courseSubjectKey(UUID workspaceId, UUID courseSubjectId) {
        return workspaceKey(workspaceId) + ":course-subject:" + requireId(courseSubjectId);
    }

    public static String resourceKey(UUID workspaceId, UUID resourceId) {
        return workspaceKey(workspaceId) + ":resource:" + requireId(resourceId);
    }

    public static String resourceVersionKey(UUID workspaceId, UUID resourceId, UUID versionId) {
        return resourceKey(workspaceId, resourceId) + ":version:" + requireId(versionId);
    }

    public static String activityKey(UUID workspaceId, UUID activityId) {
        return workspaceKey(workspaceId) + ":activity:" + requireId(activityId);
    }

    public static String resultKey(UUID workspaceId, UUID resultId) {
        return workspaceKey(workspaceId) + ":result:" + requireId(resultId);
    }

    public static String grantKey(UUID grantId) {
        return "grant:" + requireId(grantId);
    }

    // Tên native xác định. Tên leaf policy suy ra từ nội dung duy nhất của chúng, nên một
    // policy hiện có với tên template đó đã mang cấu hình mong muốn.
    public static String groupPolicyName(String groupPath) {
        return "gk-group:" + groupPath;
    }

    public static String userPolicyName(String keycloakUserId) {
        return "gk-user:" + keycloakUserId;
    }

    public static String validFromPolicyName(Instant validFrom) {
        return "gk-valid-from:" + validFrom;
    }

    public static String validUntilPolicyName(Instant expiresAt) {
        return "gk-valid-until:" + expiresAt;
    }

    public static String sourcePolicyName(String sourceDescriptorHash) {
        return "gk-source:" + sourceDescriptorHash;
    }

    public static String anySourcePolicyName(String targetKey, String scope) {
        return "gk-any:" + targetKey + "#" + scope;
    }

    public static String rootPermissionName(String targetKey, String scope) {
        return "gk-root:" + targetKey + "#" + scope;
    }

    private static UUID requireId(@Nullable UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Identifier is required");
        }
        return id;
    }
}
