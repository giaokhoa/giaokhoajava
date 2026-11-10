package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.guardians.GuardianLinkService.GuardianLinkView;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.CenterService;
import com.giaokhoa.verification.ResultPolicyProjector;
import jakarta.persistence.EntityManagerFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng tầng service của guardian link bằng lời gọi thẳng service (route HTTP thuộc task route
 * riêng): vòng đời request → approve → revoke đồng bộ membership thật của guardian group qua
 * operation và vòng reconcile; tiền đề role của cả hai phía được xét lúc gửi và xét lại lúc duyệt;
 * version lệch và quyết định trên link đã đóng đều 409; gỡ role PARENT hoặc thu hồi membership đóng
 * link đã duyệt trong cùng operation. Phần RLS chạy service qua một role runtime NOSUPERUSER
 * NOBYPASSRLS theo mẫu TeachingServiceIT, vì user của stack TEST là superuser nên tự nó không chịu
 * RLS.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianLinkServiceIT extends GuardianFixture {
    private static final String SCHEMA = "public";
    private static final String RUNTIME_ROLE = "guardian_link_service_runtime";

    @Autowired GuardianLinkService links;
    @Autowired CenterService centers;
    @Autowired KeycloakProvisioningClient directory;
    @Autowired EntityManagerFactory entityManagerFactory;

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    @DisplayName(
            "Vòng đời liên kết đồng bộ tư cách thành viên guardian group thông qua các operation")
    @Test
    void lifecycleSyncsGroupMembership() {
        GuardianCenter center = seedGuardianCenter();
        String group =
                NativePolicyTemplates.guardiansGroup(center.workspaceId(), center.studentX());
        awaitOperationSucceeded(
                jdbc,
                links.requestLink(
                                center.parentId(),
                                center.workspaceId(),
                                center.studentX(),
                                key(),
                                hash())
                        .getId());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());
        assertEquals("REQUESTED", requested.state());
        assertEquals(0, requested.version());
        assertFalse(
                directory.isRealmGroupMember(center.parent().subject(), group),
                "Request chưa duyệt thì parent chưa vào guardian group");

        awaitOperationSucceeded(
                jdbc,
                links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version(),
                                key(),
                                hash())
                        .getId());
        LinkSnapshot approved = linkSnapshot(center.workspaceId(), center.studentX());
        assertEquals("APPROVED", approved.state());
        assertEquals(1, approved.version());
        assertEquals(
                center.managerId(),
                jdbc.queryForObject(
                        "SELECT approved_by FROM guardian_link WHERE id = ?",
                        UUID.class,
                        approved.id()),
                "Duyệt ghi đúng người quyết định lên hàng link");
        assertTrue(
                directory.isRealmGroupMember(center.parent().subject(), group),
                "Sau operation duyệt SUCCEEDED, parent ở trong guardian group của đúng con");

        awaitOperationSucceeded(
                jdbc,
                links.revokeLink(
                                center.managerId(),
                                center.workspaceId(),
                                approved.id(),
                                key(),
                                hash())
                        .getId());
        LinkSnapshot revoked = linkSnapshot(center.workspaceId(), center.studentX());
        assertEquals("REVOKED", revoked.state());
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT revoked_at FROM guardian_link WHERE id = ?",
                        OffsetDateTime.class,
                        revoked.id()),
                "Gỡ link ghi revoked_at, giữ nguyên lịch sử");
        assertFalse(
                directory.isRealmGroupMember(center.parent().subject(), group),
                "Sau operation gỡ SUCCEEDED, parent rời guardian group ngay");
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE target_id = ?",
                        Integer.class,
                        approved.id()),
                "Mỗi mutation vòng đời ghi một audit event gắn operation của nó");
    }

    @DisplayName("Yêu cầu liên kết kiểm tra vai trò phụ huynh, scope học sinh và trùng lặp")
    @Test
    void requestValidatesRoleScopeDuplicates() {
        GuardianCenter center = seedGuardianCenter();
        KeycloakLogin outsider = createVerifiedUser("guardian-service-outsider");
        UUID outsiderId = seedAccount(outsider);
        KeycloakLogin otherOwner = createVerifiedUser("guardian-other-owner");
        try {
            HttpProblemException teacherRequest =
                    problemOf(() ->
                            links.requestLink(
                                    center.teacherId(),
                                    center.workspaceId(),
                                    center.studentX(),
                                    key(),
                                    hash()));
            assertEquals(HttpStatus.FORBIDDEN, teacherRequest.getStatus());
            assertEquals("GUARDIAN_PARENT_NOT_CURRENT", teacherRequest.getCode());

            HttpProblemException outsideStudent =
                    problemOf(() ->
                            links.requestLink(
                                    center.parentId(),
                                    center.workspaceId(),
                                    outsiderId,
                                    key(),
                                    hash()));
            assertEquals(HttpStatus.NOT_FOUND, outsideStudent.getStatus());
            assertEquals("GUARDIAN_STUDENT_NOT_FOUND", outsideStudent.getCode());

            links.requestLink(
                    center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
            HttpProblemException duplicate =
                    problemOf(() ->
                            links.requestLink(
                                    center.parentId(),
                                    center.workspaceId(),
                                    center.studentX(),
                                    key(),
                                    hash()));
            assertEquals(HttpStatus.CONFLICT, duplicate.getStatus());
            assertEquals("GUARDIAN_LINK_ALREADY_CURRENT", duplicate.getCode());

            UUID otherCenter = seedCenter(seedAccount(otherOwner));
            HttpProblemException crossCenter =
                    problemOf(() ->
                            links.requestLink(
                                    center.parentId(),
                                    otherCenter,
                                    center.studentX(),
                                    key(),
                                    hash()));
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    crossCenter.getStatus(),
                    "Liên kết của center này không dùng được ở center khác: parent không có"
                            + " membership PARENT ở center kia");
        } finally {
            deleteUser(outsider.subject());
            deleteUser(otherOwner.subject());
        }
    }

    @DisplayName("Quyết định kiểm tra version và state, và việc từ chối (rejection) là terminal")
    @Test
    void decisionChecksVersionAndState() {
        GuardianCenter center = seedGuardianCenter();
        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());

        HttpProblemException invalid =
                problemOf(() ->
                        links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "MAYBE",
                                requested.version(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatus());
        assertEquals("GUARDIAN_DECISION_INVALID", invalid.getCode());

        HttpProblemException stale =
                problemOf(() ->
                        links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version() + 100,
                                key(),
                                hash()));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        assertEquals("VERSION_CONFLICT", stale.getCode());
        assertEquals(
                "REQUESTED",
                linkSnapshot(center.workspaceId(), center.studentX()).state(),
                "Quyết định xung đột version không đổi trạng thái link");

        HttpProblemException revokeRequested =
                problemOf(() ->
                        links.revokeLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.CONFLICT, revokeRequested.getStatus());
        assertEquals("GUARDIAN_LINK_NOT_APPROVED", revokeRequested.getCode());

        links.decideLink(
                center.managerId(),
                center.workspaceId(),
                requested.id(),
                "REJECT",
                requested.version(),
                key(),
                hash());
        LinkSnapshot rejected = linkSnapshot(center.workspaceId(), center.studentX());
        assertEquals("REJECTED", rejected.state());
        HttpProblemException decideAgain =
                problemOf(() ->
                        links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                rejected.id(),
                                "APPROVE",
                                rejected.version(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.CONFLICT, decideAgain.getStatus());
        assertEquals("GUARDIAN_LINK_ALREADY_DECIDED", decideAgain.getCode());

        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot renewed = linkSnapshot(center.workspaceId(), center.studentX());
        assertEquals("REQUESTED", renewed.state());
        assertNotEquals(
                rejected.id(),
                renewed.id(),
                "Sau từ chối, request mới là hàng mới chứ không hồi sinh hàng đã đóng");

        HttpProblemException unknown =
                problemOf(() ->
                        links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                UUID.randomUUID(),
                                "APPROVE",
                                1L,
                                key(),
                                hash()));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());
        assertEquals("GUARDIAN_LINK_NOT_FOUND", unknown.getCode());

        List<GuardianLinkView> ownLinks =
                links.listLinksOfParent(center.workspaceId(), center.parentId());
        assertEquals(2, ownLinks.size(), "Parent thấy cả link đã từ chối và link mới của mình");
        List<GuardianLinkView> review = links.listLinksForReview(center.workspaceId());
        assertEquals(1, review.size(), "Danh sách cần xử lý chỉ còn link đang chờ duyệt");
        assertEquals(renewed.id(), review.getFirst().id());
    }

    @DisplayName("Giáo viên không thể quyết định hay revoke liên kết trực tiếp qua service")
    @Test
    void teacherCannotDecideOrRevoke() {
        GuardianCenter center = seedGuardianCenter();
        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());

        HttpProblemException decide =
                problemOf(() ->
                        links.decideLink(
                                center.teacherId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.FORBIDDEN, decide.getStatus());
        assertEquals("PERMISSION_DENIED", decide.getCode());
        assertEquals(
                "REQUESTED",
                linkSnapshot(center.workspaceId(), center.studentX()).state(),
                "Giáo viên quyết trực tiếp không đổi trạng thái link");

        HttpProblemException revoke =
                problemOf(() ->
                        links.revokeLink(
                                center.teacherId(),
                                center.workspaceId(),
                                requested.id(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.FORBIDDEN, revoke.getStatus());
        assertEquals("PERMISSION_DENIED", revoke.getCode());
    }

    @DisplayName("Gỡ vai trò phụ huynh đóng liên kết đã APPROVED trong cùng một operation")
    @Test
    void removingParentRoleClosesLink() {
        GuardianCenter center = seedGuardianCenter();
        String group =
                NativePolicyTemplates.guardiansGroup(center.workspaceId(), center.studentX());
        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());
        awaitOperationSucceeded(
                jdbc,
                links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version(),
                                key(),
                                hash())
                        .getId());
        assertTrue(directory.isRealmGroupMember(center.parent().subject(), group));

        UUID membershipId = membershipIdOf(center.workspaceId(), center.parentId());
        awaitOperationSucceeded(
                jdbc,
                centers.replaceRoles(
                                center.ownerId(),
                                center.workspaceId(),
                                membershipId,
                                Set.of(),
                                "\"" + membershipVersionOf(membershipId) + "\"",
                                key(),
                                hash())
                        .getId());
        assertEquals(
                "REVOKED",
                linkSnapshot(center.workspaceId(), center.studentX()).state(),
                "Gỡ role PARENT đóng link đã duyệt trong cùng operation");
        assertFalse(
                directory.isRealmGroupMember(center.parent().subject(), group),
                "Guardian group rời parent ngay khi operation gỡ role SUCCEEDED");
    }

    @DisplayName(
            "Revoke membership của học sinh đóng liên kết đã APPROVED trong cùng một operation")
    @Test
    void revokingMembershipClosesLink() {
        GuardianCenter center = seedGuardianCenter();
        String group =
                NativePolicyTemplates.guardiansGroup(center.workspaceId(), center.studentX());
        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());
        awaitOperationSucceeded(
                jdbc,
                links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version(),
                                key(),
                                hash())
                        .getId());
        assertTrue(directory.isRealmGroupMember(center.parent().subject(), group));

        awaitOperationSucceeded(
                jdbc,
                centers.revokeMembership(
                                center.ownerId(),
                                center.workspaceId(),
                                membershipIdOf(center.workspaceId(), center.studentX()),
                                key(),
                                hash())
                        .getId());
        assertEquals(
                "REVOKED",
                linkSnapshot(center.workspaceId(), center.studentX()).state(),
                "Thu hồi membership của học sinh đóng link đã duyệt trong cùng operation");
        assertFalse(directory.isRealmGroupMember(center.parent().subject(), group));
    }

    @DisplayName("Việc phê duyệt kiểm tra lại các tiền đề ngay tại thời điểm ra quyết định")
    @Test
    void approvalRechecksPrerequisites() {
        GuardianCenter center = seedGuardianCenter();
        links.requestLink(
                center.parentId(), center.workspaceId(), center.studentX(), key(), hash());
        LinkSnapshot requested = linkSnapshot(center.workspaceId(), center.studentX());
        awaitOperationSucceeded(
                jdbc,
                centers.revokeMembership(
                                center.ownerId(),
                                center.workspaceId(),
                                membershipIdOf(center.workspaceId(), center.parentId()),
                                key(),
                                hash())
                        .getId());
        assertEquals(
                "REQUESTED",
                linkSnapshot(center.workspaceId(), center.studentX()).state(),
                "Request chờ duyệt chưa phải nguồn nên không bị đóng theo membership");
        HttpProblemException approve =
                problemOf(() ->
                        links.decideLink(
                                center.managerId(),
                                center.workspaceId(),
                                requested.id(),
                                "APPROVE",
                                requested.version(),
                                key(),
                                hash()));
        assertEquals(HttpStatus.CONFLICT, approve.getStatus());
        assertEquals("GUARDIAN_PREREQUISITE_NOT_CURRENT", approve.getCode());
    }

    @Test
    void serviceRunsUnderNonBypassRlsRole() {
        GuardianCenter center = seedGuardianCenter();
        KeycloakLogin otherOwner = createVerifiedUser("guardian-rls-owner");
        try {
            UUID otherWorkspace = seedCenter(seedAccount(otherOwner));
            String password = UUID.randomUUID().toString();
            jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
            jdbc.execute(
                    "CREATE ROLE "
                            + RUNTIME_ROLE
                            + " LOGIN PASSWORD '"
                            + password
                            + "' NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
            jdbc.execute(
                    "GRANT SELECT, INSERT, UPDATE ON guardian_link, authz_partition,"
                            + " authz_projection, authorization_operation TO "
                            + RUNTIME_ROLE);
            jdbc.execute(
                    "GRANT SELECT ON workspace, center_membership, membership_role,"
                            + " subscription_period, user_account, sample_result TO "
                            + RUNTIME_ROLE);
            jdbc.execute("GRANT INSERT ON audit_event TO " + RUNTIME_ROLE);
            String url =
                    jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
            runtimeDataSource = new SingleConnectionDataSource(url, RUNTIME_ROLE, password, true);
            try {
                JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource);
                runtimeJdbc.execute("SET search_path TO " + SCHEMA + ",public");
                DataSourceTransactionManager runtimeTxManager =
                        new DataSourceTransactionManager(runtimeDataSource);
                WorkspaceTransaction runtimeWorkspaceTx =
                        new WorkspaceTransaction(runtimeJdbc, runtimeTxManager);
                IdempotencyService runtimeIdempotency =
                        new IdempotencyService(
                                runtimeJdbc, entityManagerFactory.createEntityManager());
                AuthorizationOperationService runtimeOperations =
                        new AuthorizationOperationService(runtimeJdbc, runtimeIdempotency);
                GuardianPolicyProjector runtimeProjector =
                        new GuardianPolicyProjector(
                                runtimeJdbc,
                                directory,
                                runtimeOperations,
                                new CenterPolicyProjector(runtimeJdbc, directory),
                                new ResultPolicyProjector(
                                        runtimeJdbc, runtimeOperations, runtimeTxManager),
                                runtimeTxManager);
                GuardianLinkService runtimeLinks =
                        new GuardianLinkService(
                                runtimeJdbc,
                                runtimeWorkspaceTx,
                                runtimeIdempotency,
                                runtimeProjector,
                                new ResourceMutationCoordinator(runtimeJdbc, runtimeTxManager),
                                new AuditRecorder(runtimeJdbc));
                TransactionTemplate runtimeTx = new TransactionTemplate(runtimeTxManager);

                assertEquals(
                        0,
                        runtimeJdbc.queryForObject(
                                "SELECT count(*) FROM guardian_link", Integer.class),
                        "Không có context thì role runtime không thấy hàng nào (fail-closed)");
                Objects.requireNonNull(
                        runtimeTx.execute(_ ->
                                runtimeLinks.requestLink(
                                        center.parentId(),
                                        center.workspaceId(),
                                        center.studentX(),
                                        key(),
                                        hash())));
                assertEquals(
                        0,
                        runtimeJdbc.queryForObject(
                                "SELECT count(*) FROM guardian_link", Integer.class),
                        "Ngoài transaction có context, hàng vẫn vô hình với role runtime");
                Integer visible =
                        Objects.requireNonNull(
                                runtimeTx.execute(_ -> {
                                    runtimeWorkspaceTx.applyContext(center.workspaceId());
                                    return Objects.requireNonNull(
                                            runtimeJdbc.queryForObject(
                                                    "SELECT count(*) FROM guardian_link",
                                                    Integer.class));
                                }));
                assertEquals(1, visible, "Đúng context workspace thì thấy đúng hàng của nó");
                assertThrows(
                        DataAccessException.class,
                        () ->
                                runtimeTx.executeWithoutResult(_ -> {
                                    runtimeWorkspaceTx.applyContext(center.workspaceId());
                                    runtimeJdbc.update(
                                            "INSERT INTO guardian_link (id, workspace_id,"
                                                    + " parent_user_id, student_user_id,"
                                                    + " state, requested_by) VALUES"
                                                    + " (?, ?, ?, ?, 'REQUESTED', ?)",
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            center.parentId(),
                                            center.studentX(),
                                            center.parentId());
                                }),
                        "Ghi hàng của workspace khác với context bị WITH CHECK của RLS chặn");
                LinkSnapshot created = linkSnapshot(center.workspaceId(), center.studentX());
                HttpProblemException crossWorkspace =
                        assertThrows(
                                HttpProblemException.class,
                                () ->
                                        runtimeTx.execute(_ ->
                                                runtimeLinks.decideLink(
                                                        center.managerId(),
                                                        otherWorkspace,
                                                        created.id(),
                                                        "APPROVE",
                                                        created.version(),
                                                        key(),
                                                        hash())));
                assertEquals(
                        HttpStatus.NOT_FOUND,
                        crossWorkspace.getStatus(),
                        "Link của workspace khác vô hình qua service dưới role runtime");
            } finally {
                if (runtimeDataSource != null) {
                    runtimeDataSource.destroy();
                }
                jdbc.execute(
                        "REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
                jdbc.execute("REVOKE USAGE ON SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
                jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
            }
        } finally {
            deleteUser(otherOwner.subject());
        }
    }

    // ---------- Hỗ trợ ----------

    private record LinkSnapshot(UUID id, String state, long version) {}

    private LinkSnapshot linkSnapshot(UUID workspaceId, UUID studentUserId) {
        return jdbc.queryForObject(
                "SELECT id, state, version FROM guardian_link WHERE workspace_id = ?"
                        + " AND student_user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, _) ->
                        new LinkSnapshot(
                                rs.getObject("id", UUID.class),
                                rs.getString("state"),
                                rs.getLong("version")),
                workspaceId,
                studentUserId);
    }

    private UUID membershipIdOf(UUID workspaceId, UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM center_membership WHERE workspace_id = ? AND user_id = ?",
                        UUID.class,
                        workspaceId,
                        userId),
                "membership");
    }

    private long membershipVersionOf(UUID membershipId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT version FROM center_membership WHERE id = ?",
                        Long.class,
                        membershipId),
                "membership version");
    }

    private static HttpProblemException problemOf(Executable action) {
        return assertThrows(HttpProblemException.class, action);
    }

    private static String key() {
        return "guardian-service-" + UUID.randomUUID();
    }

    private static String hash() {
        // Check constraint của authorization_operation đòi payload_hash là SHA-256 hex.
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
