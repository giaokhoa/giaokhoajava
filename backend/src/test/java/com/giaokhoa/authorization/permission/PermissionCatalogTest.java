package com.giaokhoa.authorization.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.giaokhoa.authorization.permission.PermissionCatalog.ActivityScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.ClassScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.CourseScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.CourseSubjectScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.GrantScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.PlatformScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.ProfileScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.ResourceScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.ResourceVersionScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.ResultScope;
import com.giaokhoa.authorization.permission.PermissionCatalog.Scope;
import com.giaokhoa.authorization.permission.PermissionCatalog.WorkspaceScope;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Danh mục scope trong test này chép đúng bảng scope cố định của hợp đồng authorization; đây là
 * nguồn độc lập để khóa cả PermissionCatalog lẫn template native không lệch hợp đồng.
 */
class PermissionCatalogTest {

    @Test
    void platformScopesMatchContract() {
        assertEquals(
                List.of(
                        "center.create",
                        "center.list",
                        "subscription.confirm",
                        "account.recover",
                        "fixture.manage"),
                PermissionCatalog.names(PlatformScope.values()));
    }

    @Test
    void profileScopesMatchContract() {
        assertEquals(
                List.of("profile.read", "workspace.list", "recovery.request", "history.read"),
                PermissionCatalog.names(ProfileScope.values()));
    }

    @Test
    void workspaceScopesMatchContract() {
        assertEquals(
                List.of(
                        "enter",
                        "read",
                        "class.list",
                        "class.create",
                        "membership.list",
                        "membership.invite",
                        "membership.role.assign",
                        "membership.role.revoke",
                        "membership.revoke",
                        "manager.assign",
                        "manager.revoke",
                        "student.issue",
                        "guardian.request",
                        "guardian.approve",
                        "guardian.revoke"),
                PermissionCatalog.names(WorkspaceScope.values()));
    }

    @Test
    void classScopesMatchContract() {
        assertEquals(
                List.of(
                        "read",
                        "update",
                        "course.list",
                        "course.create",
                        "roster.read",
                        "roster.add",
                        "roster.revoke",
                        "homeroom.assign",
                        "homeroom.revoke",
                        "completion.read"),
                PermissionCatalog.names(ClassScope.values()));
    }

    @Test
    void courseScopesMatchContract() {
        assertEquals(
                List.of("read", "update", "subject.add"),
                PermissionCatalog.names(CourseScope.values()));
    }

    @Test
    void courseSubjectScopesMatchContract() {
        assertEquals(
                List.of(
                        "read",
                        "roster.read",
                        "enrollment.add",
                        "enrollment.revoke",
                        "teacher.assign",
                        "teacher.revoke",
                        "resource.create",
                        "activity.assign",
                        "completion.read"),
                PermissionCatalog.names(CourseSubjectScope.values()));
    }

    @Test
    void resourceVersionScopesMatchContract() {
        assertEquals(
                List.of(
                        "content.read",
                        "answer.read",
                        "use",
                        "fork",
                        "share",
                        "library.save",
                        "review",
                        "practice"),
                PermissionCatalog.names(ResourceVersionScope.values()));
    }

    @Test
    void resourceScopesMatchContract() {
        assertEquals(
                List.of("draft.edit", "version.create"),
                PermissionCatalog.names(ResourceScope.values()));
    }

    @Test
    void activityScopesMatchContract() {
        assertEquals(
                List.of("read", "submit", "schedule.extend", "attempt.open", "reassign"),
                PermissionCatalog.names(ActivityScope.values()));
    }

    @Test
    void resultScopesMatchContract() {
        assertEquals(
                List.of("read", "read.published", "grade", "publish"),
                PermissionCatalog.names(ResultScope.values()));
    }

    @Test
    void grantScopesMatchContract() {
        assertEquals(List.of("accept", "revoke"), PermissionCatalog.names(GrantScope.values()));
    }

    @Test
    void typedConstantsCarryNativeNames() {
        assertEquals("class.create", WorkspaceScope.CLASS_CREATE.scopeName());
        assertEquals("membership.role.assign", WorkspaceScope.MEMBERSHIP_ROLE_ASSIGN.scopeName());
        assertEquals("content.read", ResourceVersionScope.CONTENT_READ.scopeName());
        assertEquals("answer.read", ResourceVersionScope.ANSWER_READ.scopeName());
        assertEquals("read.published", ResultScope.READ_PUBLISHED.scopeName());
        assertEquals("fixture.manage", PlatformScope.FIXTURE_MANAGE.scopeName());
    }

    @Test
    void noDuplicateScopeNamesWithinAnyType() {
        List<Scope[]> groups =
                List.of(
                        PlatformScope.values(),
                        ProfileScope.values(),
                        WorkspaceScope.values(),
                        ClassScope.values(),
                        CourseScope.values(),
                        CourseSubjectScope.values(),
                        ResourceVersionScope.values(),
                        ResourceScope.values(),
                        ActivityScope.values(),
                        ResultScope.values(),
                        GrantScope.values());
        for (Scope[] group : groups) {
            List<String> names = PermissionCatalog.names(group);
            assertEquals(names.size(), new HashSet<>(names).size(), () -> "trùng scope: " + names);
        }
    }

    @DisplayName("Native template phơi bày đúng chính xác các scope trong catalog")
    @Test
    void nativeTemplatesMatchCatalogScopes() {
        assertEquals(
                PermissionCatalog.names(PlatformScope.values()),
                NativePolicyTemplates.TargetType.PLATFORM.scopes());
        assertEquals(
                PermissionCatalog.names(ProfileScope.values()),
                NativePolicyTemplates.TargetType.PROFILE.scopes());
        assertEquals(
                PermissionCatalog.names(WorkspaceScope.values()),
                NativePolicyTemplates.TargetType.WORKSPACE.scopes());
        assertEquals(
                PermissionCatalog.names(ClassScope.values()),
                NativePolicyTemplates.TargetType.CLASS.scopes());
        assertEquals(
                PermissionCatalog.names(CourseScope.values()),
                NativePolicyTemplates.TargetType.COURSE.scopes());
        assertEquals(
                PermissionCatalog.names(CourseSubjectScope.values()),
                NativePolicyTemplates.TargetType.COURSE_SUBJECT.scopes());
        assertEquals(
                PermissionCatalog.names(ResourceVersionScope.values()),
                NativePolicyTemplates.TargetType.RESOURCE_VERSION.scopes());
        assertEquals(
                PermissionCatalog.names(ResourceScope.values()),
                NativePolicyTemplates.TargetType.RESOURCE.scopes());
        assertEquals(
                PermissionCatalog.names(ActivityScope.values()),
                NativePolicyTemplates.TargetType.ACTIVITY.scopes());
        assertEquals(
                PermissionCatalog.names(ResultScope.values()),
                NativePolicyTemplates.TargetType.RESULT.scopes());
        assertEquals(
                PermissionCatalog.names(GrantScope.values()),
                NativePolicyTemplates.TargetType.GRANT.scopes());
        assertEquals(11, NativePolicyTemplates.TargetType.values().length);
    }
}
