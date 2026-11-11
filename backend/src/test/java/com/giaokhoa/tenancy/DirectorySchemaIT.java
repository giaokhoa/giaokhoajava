package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.support.IntegrationEnvironment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

// Harness tối thiểu: chỉ kiểm chứng schema/bảng bằng JDBC thuần trên schema riêng nên tự chạy
// Flyway và tự cấp JdbcTemplate, không dựng ApplicationContext của ứng dụng. Mỗi test vẫn chạy
// trong một giao dịch và rollback ở cuối như khi dùng @Transactional của Spring Test.
class DirectorySchemaIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "directory_schema_" + UUID.randomUUID().toString().replace("-", "");

    private static JdbcTemplate jdbc;
    private static JdbcTemplate surgery;
    private static DataSourceTransactionManager txManager;
    private TransactionStatus txStatus;

    @BeforeAll
    static void migrateSchema() {
        var migrator =
                new DriverManagerDataSource(
                        testDatabaseUrl(),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_PASSWORD")));
        Flyway.configure()
                .dataSource(migrator)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .load()
                .migrate();
        surgery = new JdbcTemplate(migrator);
        var source =
                new DriverManagerDataSource(
                        testDatabaseUrl() + "?currentSchema=" + SCHEMA + ",public",
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_PASSWORD")));
        jdbc = new JdbcTemplate(source);
        txManager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void beginTestTransaction() {
        txStatus = txManager.getTransaction(new DefaultTransactionDefinition());
    }

    @AfterEach
    void rollbackTestTransaction() {
        txManager.rollback(txStatus);
    }

    @AfterAll
    static void removeSchema() {
        surgery.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @DisplayName("Bootstrap center với đúng một owner và các role bổ sung")
    @Test
    void bootstrapsCenterWithOneOwner() {
        UUID owner = user();
        UUID workspace = center(owner, true);
        UUID membership =
                jdbc.queryForObject(
                        "SELECT id FROM center_membership WHERE workspace_id = ?",
                        UUID.class,
                        workspace);
        jdbc.update(
                "INSERT INTO membership_role(membership_id, role) VALUES (?, 'MANAGER')",
                membership);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM membership_role WHERE membership_id = ?",
                        Integer.class,
                        membership));
    }

    @Test
    void centerWithoutOwnerMembershipIsRejected() {
        UUID owner = user();
        center(owner, false);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void otherUserCannotBecomeOwner() {
        UUID owner = user();
        UUID impostor = user();
        UUID workspace = center(owner, true);
        UUID membership = member(workspace, impostor);
        jdbc.update(
                "INSERT INTO membership_role(membership_id, role) VALUES (?, 'OWNER')", membership);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void cannotRevokeTheDesignatedOwner() {
        UUID owner = user();
        UUID workspace = center(owner, true);
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() "
                        + "WHERE workspace_id = ? AND user_id = ?",
                workspace,
                owner);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void removingOwnerRoleIsRejected() {
        UUID owner = user();
        UUID workspace = center(owner, true);
        jdbc.update(
                "DELETE FROM membership_role WHERE role = 'OWNER' AND membership_id IN "
                        + "(SELECT id FROM center_membership WHERE workspace_id = ?)",
                workspace);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void changingCenterOwnerIsRejected() {
        UUID owner = user();
        UUID replacement = user();
        UUID workspace = center(owner, true);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE center SET owner_user_id = ? WHERE workspace_id = ?",
                                replacement,
                                workspace));
    }

    @Test
    void invalidRoleIsRejected() {
        UUID owner = user();
        UUID workspace = center(owner, true);
        UUID membership =
                jdbc.queryForObject(
                        "SELECT id FROM center_membership WHERE workspace_id = ?",
                        UUID.class,
                        workspace);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO membership_role(membership_id, role) VALUES (?, 'CUSTOM')",
                                membership));
    }

    @DisplayName("Account không có personal workspace bị từ chối")
    @Test
    void accountWithoutPersonalWsRejected() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,false,'SELF_REGISTERED','PENDING')",
                id,
                "sub-" + id,
                "user-" + id);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @DisplayName("Không thể gỡ personal workspace duy nhất của một account")
    @Test
    void cannotRemoveOnlyPersonalWs() {
        UUID owner = user();
        jdbc.update("DELETE FROM workspace WHERE id = ?", personal(owner));
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @DisplayName("Personal workspace trùng và membership hiện hành trùng đều bị từ chối")
    @Test
    void duplicatePersonalWsRejected() {
        UUID owner = user();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                                UUID.randomUUID(),
                                owner));
    }

    @DisplayName("Membership trùng giữa trạng thái PENDING và ACTIVE bị từ chối")
    @Test
    void duplicateMembershipIsRejected() {
        UUID owner = user();
        UUID workspace = center(owner, true);
        assertThrows(DataAccessException.class, () -> member(workspace, owner));
    }

    @DisplayName("Membership (của center) không thể thuộc về personal workspace")
    @Test
    void membershipNotForPersonalWs() {
        UUID owner = user();
        assertThrows(DataAccessException.class, () -> member(personal(owner), owner));
    }

    @Test
    void systemCannotHaveSubscription() {
        UUID user = user();
        UUID system = UUID.randomUUID();
        jdbc.update("INSERT INTO workspace(id,kind,state) VALUES (?, 'SYSTEM', 'ACTIVE')", system);
        assertThrows(
                DataAccessException.class,
                () ->
                        period(
                                system,
                                user,
                                Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-02-01T00:00:00Z")));
    }

    @DisplayName("Subscription theo khoảng nửa mở (half-open) và đòi hỏi thời lượng dương")
    @Test
    void subscriptionHalfOpenPositive() {
        UUID owner = user();
        UUID personal = personal(owner);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-02-01T00:00:00Z");
        period(personal, owner, start, end);
        assertEquals(1, activePeriods(personal, start));
        assertEquals(0, activePeriods(personal, end));
        assertThrows(DataAccessException.class, () -> period(personal, owner, end, start));
    }

    @DisplayName("Liên kết workspace của center phải trỏ ngược về đúng center của nó")
    @Test
    void centerLinkPointsToOwnCenter() {
        UUID owner = user();
        UUID workspace = UUID.randomUUID();
        UUID centerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace(id,kind,center_id,state) VALUES (?, 'CENTER', ?, 'ACTIVE')",
                workspace,
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,owner_user_id,state)"
                        + " VALUES (?, ?, ?, 'Center A', ?, 'ACTIVE')",
                centerId,
                workspace,
                "org-" + centerId,
                owner);
        UUID membership = member(workspace, owner);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?, 'OWNER')", membership);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @DisplayName("Membership đã revoke không thể được kích hoạt lại với các role cũ")
    @Test
    void revokedMembershipStaysRevoked() {
        UUID owner = user();
        UUID member = user();
        UUID workspace = center(owner, true);
        UUID membership = member(workspace, member);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?, 'TEACHER')",
                membership);
        jdbc.update("UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?", membership);
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                membership);

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE center_membership SET state = 'ACTIVE', revoked_at = NULL WHERE id = ?",
                                membership));
    }

    @DisplayName("Membership đã revoke chỉ được cấp lại thông qua một membership mới")
    @Test
    void regrantOnlyViaNewMembership() {
        UUID owner = user();
        UUID member = user();
        UUID workspace = center(owner, true);
        UUID previous = member(workspace, member);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?, 'TEACHER')", previous);
        jdbc.update("UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?", previous);
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                previous);

        UUID replacement = member(workspace, member);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?, 'STUDENT')",
                replacement);
        jdbc.update("UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?", replacement);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM center_membership WHERE workspace_id = ? AND user_id = ?",
                        Integer.class,
                        workspace,
                        member));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM center_membership WHERE workspace_id = ? AND user_id = ?"
                                + " AND state = 'ACTIVE'",
                        Integer.class,
                        workspace,
                        member));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM membership_role WHERE membership_id = ? AND role = 'TEACHER'",
                        Integer.class,
                        previous));
    }

    @Test
    void revokedMembershipCannotBeRewritten() {
        UUID owner = user();
        UUID member = user();
        UUID workspace = center(owner, true);
        UUID membership = member(workspace, member);
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                membership);

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE center_membership SET joined_at = joined_at - interval '1 day' WHERE id = ?",
                                membership));
    }

    @Test
    void activeMembershipCannotReturnToPending() {
        UUID owner = user();
        UUID member = user();
        UUID workspace = center(owner, true);
        UUID membership = member(workspace, member);
        jdbc.update("UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?", membership);

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE center_membership SET state = 'PENDING' WHERE id = ?",
                                membership));
    }

    @Test
    void nonOwnerMembershipCannotBeHardDeleted() {
        UUID owner = user();
        UUID member = user();
        UUID workspace = center(owner, true);
        UUID membership = member(workspace, member);
        jdbc.update("UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?", membership);
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                membership);

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update("DELETE FROM center_membership WHERE id = ?", membership));
    }

    @Test
    void confirmedSubscriptionCannotBeHardDeleted() {
        UUID owner = user();
        UUID workspace = personal(owner);
        period(
                workspace,
                owner,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "DELETE FROM subscription_period WHERE workspace_id = ?",
                                workspace));
    }

    @DisplayName("Tham chiếu subscription đã confirm không thể bị ghi lại")
    @Test
    void confirmedSubscriptionRefLocked() {
        UUID owner = user();
        UUID workspace = personal(owner);
        period(
                workspace,
                owner,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE subscription_period SET confirmation_ref = 'forged' WHERE workspace_id = ?",
                                workspace));
    }

    @DisplayName("Ngày của subscription đã bắt đầu không thể bị ghi lại")
    @Test
    void startedSubscriptionDatesLocked() {
        UUID owner = user();
        UUID workspace = personal(owner);
        period(
                workspace,
                owner,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE subscription_period SET expires_at = expires_at + interval '1 day'"
                                        + " WHERE workspace_id = ?",
                                workspace));
    }

    @DisplayName("Subscription không thể thay đổi sau khi bắt đầu ngay trong cùng transaction")
    @Test
    void subscriptionLockedAfterStart() {
        UUID owner = user();
        UUID workspace = personal(owner);
        OffsetDateTime databaseStart =
                jdbc.queryForObject(
                        "SELECT clock_timestamp() + interval '2 seconds'", OffsetDateTime.class);
        assertNotNull(databaseStart);
        Instant start = databaseStart.toInstant();
        period(workspace, owner, start, start.plusSeconds(86400));
        assertEquals(
                Boolean.TRUE,
                jdbc.queryForObject(
                        "SELECT current_timestamp < valid_from"
                                + " FROM subscription_period WHERE workspace_id = ?",
                        Boolean.class,
                        workspace));

        jdbc.query(
                "SELECT pg_sleep_until(valid_from + interval '200 milliseconds')"
                        + " FROM subscription_period WHERE workspace_id = ?",
                _ -> {},
                workspace);
        assertEquals(
                Boolean.TRUE,
                jdbc.queryForObject(
                        "SELECT clock_timestamp() > valid_from"
                                + " AND current_timestamp < valid_from FROM subscription_period WHERE workspace_id = ?",
                        Boolean.class,
                        workspace));
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE subscription_period"
                                        + " SET expires_at = expires_at + interval '1 day', version = version + 1"
                                        + " WHERE workspace_id = ?",
                                workspace));
    }

    @DisplayName("Ngày subscription trong tương lai cập nhật được kèm version")
    @Test
    void futureDatesUpdatableWithVersion() {
        UUID owner = user();
        UUID workspace = personal(owner);
        period(
                workspace,
                owner,
                Instant.parse("2099-01-01T00:00:00Z"),
                Instant.parse("2099-02-01T00:00:00Z"));

        assertEquals(
                1,
                jdbc.update(
                        "UPDATE subscription_period"
                                + " SET expires_at = expires_at + interval '1 day', version = version + 1,"
                                + " updated_at = now() WHERE workspace_id = ? AND version = 0",
                        workspace));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT version FROM subscription_period WHERE workspace_id = ?",
                        Integer.class,
                        workspace));
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,false,'CENTER_ISSUED','PENDING')",
                id,
                "sub-" + id,
                "user-" + id);
        jdbc.update(
                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                UUID.randomUUID(),
                id);
        return id;
    }

    private UUID personal(UUID owner) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        owner));
    }

    private UUID center(UUID owner, boolean withOwner) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace(id,kind,center_id,state) VALUES (?, 'CENTER', ?, 'ACTIVE')",
                workspace,
                center);
        jdbc.update(
                "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,owner_user_id,state)"
                        + " VALUES (?, ?, ?, 'Center A', ?, 'ACTIVE')",
                center,
                workspace,
                "org-" + center,
                owner);
        if (withOwner) {
            UUID membership = member(workspace, owner);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?, 'OWNER')",
                    membership);
        }
        return workspace;
    }

    private UUID member(UUID workspace, UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                        + " VALUES (?,?,?,'PENDING',now())",
                id,
                workspace,
                user);
        return id;
    }

    private void period(UUID workspace, UUID user, Instant from, Instant to) {
        jdbc.update(
                "INSERT INTO subscription_period"
                        + "(id,workspace_id,valid_from,expires_at,confirmation_ref,confirmed_by,confirmed_at)"
                        + " VALUES (?,?,?,?,?,?,now())",
                UUID.randomUUID(),
                workspace,
                from.atOffset(ZoneOffset.UTC),
                to.atOffset(ZoneOffset.UTC),
                "confirmation-" + UUID.randomUUID(),
                user);
    }

    private int activePeriods(UUID workspace, Instant at) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period"
                                + " WHERE workspace_id = ? AND valid_from <= ? AND expires_at > ?",
                        Integer.class,
                        workspace,
                        at.atOffset(ZoneOffset.UTC),
                        at.atOffset(ZoneOffset.UTC)));
    }
}
