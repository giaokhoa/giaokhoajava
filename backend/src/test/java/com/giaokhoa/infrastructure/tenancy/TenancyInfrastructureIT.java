package com.giaokhoa.infrastructure.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.resources.ResourceCopies;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import com.giaokhoa.support.IntegrationEnvironment;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng hạ tầng schema-per-tenant trên PostgreSQL TEST thật: provisioning tạo schema và chạy
 * migration dòng tenant, search_path không rò qua pool, hai tenant không thấy dữ liệu của nhau qua
 * cùng một EntityManagerFactory, và code JPA với JdbcTemplate dùng chung một transaction.
 */
@DisplayName("Hạ tầng tenancy: mỗi workspace một schema")
class TenancyInfrastructureIT extends IntegrationEnvironment {

    private static String databaseUrl;
    private static DataSource migrationDataSource;
    private static DataSource runtimeDataSource;
    private static JdbcTemplate raw;
    private static TenantSchemaProvisioner provisioner;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdWorkspaces = new ArrayList<>();

    @BeforeAll
    static void loadEnvironment() {
        databaseUrl = testDatabaseUrl();
        migrationDataSource =
                new DriverManagerDataSource(
                        databaseUrl,
                        env("TEST_DATABASE_MIGRATOR_USERNAME"),
                        env("TEST_DATABASE_MIGRATOR_PASSWORD"));
        runtimeDataSource =
                new DriverManagerDataSource(
                        databaseUrl, env("TEST_DATABASE_USERNAME"), env("TEST_DATABASE_PASSWORD"));
        raw = new JdbcTemplate(migrationDataSource);
        provisioner = new TenantSchemaProvisioner(migrationDataSource);
    }

    @AfterEach
    void cleanup() {
        for (UUID workspaceId : createdWorkspaces) {
            raw.execute(
                    "DROP SCHEMA IF EXISTS "
                            + TenantContext.schemaNameFor(workspaceId)
                            + " CASCADE");
        }
        if (!createdWorkspaces.isEmpty()) {
            new TransactionTemplate(new DataSourceTransactionManager(migrationDataSource))
                    .executeWithoutResult(status -> {
                        raw.update(
                                "DELETE FROM workspace WHERE id IN ("
                                        + placeholders(createdWorkspaces.size())
                                        + ")",
                                createdWorkspaces.toArray());
                        raw.update(
                                "DELETE FROM user_account WHERE id IN ("
                                        + placeholders(createdUsers.size())
                                        + ")",
                                createdUsers.toArray());
                    });
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "Tạo workspace mới thì schema tenant được tạo, migrate đủ 25 bảng và chạy lại không lỗi")
    void provisioningCreatesSchema() {
        UUID workspaceId = newWorkspace("provisioning").workspaceId();

        String schema = provisioner.provisionSchema(workspaceId);

        assertEquals(TenantContext.schemaNameFor(workspaceId), schema);
        Long tables =
                raw.queryForObject(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = ?", Long.class, schema);
        assertEquals(27L, tables);
        Long history =
                raw.queryForObject(
                        "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                        Long.class);
        assertNotNull(history);
        assertTrue(history >= 1L);
        assertEquals(
                schema,
                raw.queryForObject(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId));
        provisioner.provisionSchema(workspaceId);
        List<TenantMigrationRunner.Result> results =
                new TenantMigrationRunner(raw, provisioner).migrateAllTenants();
        assertTrue(
                results.stream()
                        .filter(result -> result.workspaceId().equals(workspaceId))
                        .allMatch(result -> result.success()));
    }

    @Test
    @DisplayName("Runner cấp được schema cho workspace chưa từng cấp (schema_name còn NULL)")
    void runnerProvisionsWorkspaceWithoutSchema() {
        UUID workspaceId = newWorkspace("runner-retry").workspaceId();
        assertNull(
                raw.queryForObject(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId));

        List<TenantMigrationRunner.Result> results =
                new TenantMigrationRunner(raw, provisioner).migrateAllTenants();

        TenantMigrationRunner.Result own =
                results.stream()
                        .filter(result -> result.workspaceId().equals(workspaceId))
                        .findFirst()
                        .orElseThrow();
        assertTrue(own.success());
        assertEquals(
                TenantContext.schemaNameFor(workspaceId),
                raw.queryForObject(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId));
    }

    @Test
    @DisplayName("Schema tenant không còn bảng nào bật RLS hay policy")
    void tenantSchemaHasNoRls() {
        UUID workspaceId = newWorkspace("no-rls").workspaceId();
        String schema = provisioner.provisionSchema(workspaceId);

        Long rlsTables =
                raw.queryForObject(
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n"
                                + " ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relrowsecurity",
                        Long.class,
                        schema);
        assertEquals(0L, rlsTables);
        Long policies =
                raw.queryForObject(
                        "SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?",
                        Long.class,
                        schema);
        assertEquals(0L, policies);
    }

    @Test
    @DisplayName("Runtime role có đủ quyền theo mô hình grant theo schema trên schema tenant")
    void runtimeRoleHasSchemaGrants() {
        UUID workspaceId = newWorkspace("runtime-grants").workspaceId();
        String schema = provisioner.provisionSchema(workspaceId);

        assertEquals(
                1L,
                raw.queryForObject(
                        "SELECT count(*) FROM pg_roles WHERE rolname = 'giaokhoa_runtime'",
                        Long.class));
        assertEquals(
                Boolean.TRUE,
                raw.queryForObject(
                        "SELECT has_schema_privilege('giaokhoa_runtime', ?, 'USAGE')",
                        Boolean.class,
                        schema));
        for (String table : List.of("school_class", "library_item")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertEquals(
                        Boolean.TRUE,
                        raw.queryForObject(
                                "SELECT has_table_privilege('giaokhoa_runtime', ?, ?)",
                                Boolean.class,
                                schema + "." + table,
                                privilege),
                        "Thiếu quyền " + privilege + " trên " + schema + "." + table);
            }
        }
    }

    @Test
    @DisplayName(
            "Gọi applyContext trực tiếp thì TenantContext được khôi phục khi transaction kết thúc,"
                    + " cả khi commit lẫn rollback")
    void directApplyContextRestoresTenantContext() {
        ProbeWorkspace probe = newWorkspace("context-restore");
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            WorkspaceTransaction workspaceTransaction = context.getBean(WorkspaceTransaction.class);
            TransactionTemplate transactions =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            TenantContext.clear();

            transactions.executeWithoutResult(status -> {
                workspaceTransaction.applyContext(probe.workspaceId());
                TenantContext.Context inside = TenantContext.current();
                assertNotNull(inside);
                assertEquals(probe.workspaceId(), inside.workspaceId());
            });
            assertNull(TenantContext.current());

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            transactions.executeWithoutResult(status -> {
                                workspaceTransaction.applyContext(probe.workspaceId());
                                throw new IllegalStateException("rollback chủ đích");
                            }));
            assertNull(TenantContext.current());
        }
    }

    @Test
    @DisplayName("Connection trả về pool không mang search_path của tenant trước sang lần mượn sau")
    void searchPathDoesNotLeak() throws Exception {
        UUID workspaceId = newWorkspace("searchpath").workspaceId();
        String schema = provisioner.provisionSchema(workspaceId);
        HikariConfig config = poolConfig(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            TenantConnectionProvider provider = new TenantConnectionProvider(pool);
            try (Connection tenantConnection = provider.getConnection(schema)) {
                assertTrue(searchPath(tenantConnection).contains(schema));
                assertTrue(queryLong(tenantConnection, "SELECT count(*) FROM workspace") >= 1L);
                provider.releaseConnection(schema, tenantConnection);
            }

            try (Connection pooled = pool.getConnection()) {
                assertFalse(searchPath(pooled).contains(schema));
            }
            try (Connection any = provider.getAnyConnection()) {
                assertFalse(searchPath(any).contains(schema));
                provider.releaseAnyConnection(any);
            }
        }
    }

    @Test
    @DisplayName("Hai tenant qua cùng một EntityManagerFactory không thấy dữ liệu của nhau")
    void tenantsAreIsolated() {
        ProbeWorkspace a = newWorkspace("isolation-a");
        ProbeWorkspace b = newWorkspace("isolation-b");
        UUID workspaceA = a.workspaceId();
        UUID workspaceB = b.workspaceId();
        String schemaA = provisioner.provisionSchema(workspaceA);
        String schemaB = provisioner.provisionSchema(workspaceB);
        HikariConfig config = poolConfig(2);
        try (HikariDataSource pool = new HikariDataSource(config);
                SessionFactory sessionFactory =
                        new MetadataSources(
                                        new StandardServiceRegistryBuilder()
                                                .applySetting(
                                                        AvailableSettings
                                                                .MULTI_TENANT_CONNECTION_PROVIDER,
                                                        new TenantConnectionProvider(pool))
                                                .applySetting(
                                                        AvailableSettings
                                                                .MULTI_TENANT_IDENTIFIER_RESOLVER,
                                                        new WorkspaceTenantResolver())
                                                .applySetting("hibernate.hbm2ddl.auto", "none")
                                                .build())
                                .addAnnotatedClass(ProbeSubscriptionPeriod.class)
                                .buildMetadata()
                                .buildSessionFactory()) {
            UUID probeId = UUID.randomUUID();
            TenantContext.set(workspaceA, schemaA);
            try (Session session = sessionFactory.openSession()) {
                session.beginTransaction();
                session.persist(new ProbeSubscriptionPeriod(probeId, workspaceA, a.userId()));
                session.getTransaction().commit();
            }
            assertEquals(0L, countViaJpa(sessionFactory, workspaceB, schemaB, probeId));
            assertEquals(1L, countViaJpa(sessionFactory, workspaceA, schemaA, probeId));
            assertEquals(
                    0L,
                    raw.queryForObject(
                            "SELECT count(*) FROM public.subscription_period WHERE id = ?",
                            Long.class,
                            probeId));
            TenantContext.set(workspaceA, schemaA);
            try (Session session = sessionFactory.openSession()) {
                Long workspaces =
                        session.createNativeQuery("SELECT count(*) FROM workspace", Long.class)
                                .getSingleResult();
                assertTrue(workspaces >= 2L);
            }
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName(
            "JdbcTemplate thấy dữ liệu JPA chưa commit trong cùng transaction và rollback cùng nhau")
    void jpaAndJdbcShareTransaction() {
        ProbeWorkspace a = newWorkspace("bridge-a");
        ProbeWorkspace b = newWorkspace("bridge-b");
        UUID workspaceA = a.workspaceId();
        UUID workspaceB = b.workspaceId();
        String schemaA = provisioner.provisionSchema(workspaceA);
        String schemaB = provisioner.provisionSchema(workspaceB);
        UUID probeId = UUID.randomUUID();
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            WorkspaceTransaction workspaceTransaction = context.getBean(WorkspaceTransaction.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            workspaceTransaction.inWorkspace(
                    workspaceA,
                    () -> {
                        EntityManager em =
                                Objects.requireNonNull(
                                        EntityManagerFactoryUtils.getTransactionalEntityManager(
                                                context.getBean(EntityManagerFactory.class)));
                        em.persist(new ProbeSubscriptionPeriod(probeId, workspaceA, a.userId()));
                        em.flush();
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM subscription_period WHERE id = ?",
                                        Long.class,
                                        probeId));
                        return null;
                    });
            assertEquals(1L, countIn(schemaA, probeId));
            assertEquals(0L, countIn(schemaB, probeId));
            assertEquals(0L, countIn("public", probeId));

            UUID rolledBackId = UUID.randomUUID();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            workspaceTransaction.inWorkspace(
                                    workspaceA,
                                    () -> {
                                        jdbc.update(
                                                "INSERT INTO subscription_period (id, workspace_id,"
                                                        + " valid_from, expires_at, confirmation_ref,"
                                                        + " confirmed_by, confirmed_at) VALUES (?, ?,"
                                                        + " now(), now() + interval '1 hour', ?, ?,"
                                                        + " now())",
                                                rolledBackId,
                                                workspaceA,
                                                "probe-rb-"
                                                        + rolledBackId.toString().substring(0, 8),
                                                a.userId());
                                        throw new IllegalStateException("rollback chủ đích");
                                    }));
            assertEquals(0L, countIn(schemaA, rolledBackId));
        }
    }

    private record ProbeWorkspace(UUID userId, UUID workspaceId) {}

    @Test
    @DisplayName(
            "Chuyển tenant trong transaction JPA bị chặn trước khi flush dữ kiện sang schema khác")
    void rejectCrossTenantFlush() {
        ProbeWorkspace a = newWorkspace("boundary-a");
        ProbeWorkspace b = newWorkspace("boundary-b");
        String schemaA = provisioner.provisionSchema(a.workspaceId());
        String schemaB = provisioner.provisionSchema(b.workspaceId());
        UUID id = UUID.randomUUID();
        try (var context = new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            WorkspaceTransaction tx = context.getBean(WorkspaceTransaction.class);
            tx.inWorkspace(
                    a.workspaceId(),
                    () -> {
                        EntityManager em =
                                Objects.requireNonNull(
                                        EntityManagerFactoryUtils.getTransactionalEntityManager(
                                                context.getBean(EntityManagerFactory.class)));
                        assertEquals(schemaA, em.unwrap(Session.class).getTenantIdentifierValue());
                        em.persist(new ProbeSubscriptionPeriod(id, a.workspaceId(), a.userId()));
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        tx.inWorkspace(
                                                b.workspaceId(),
                                                () -> {
                                                    em.flush();
                                                    return null;
                                                }));
                        em.flush();
                        return null;
                    });
        }
        assertEquals(1L, countIn(schemaA, id));
        assertEquals(0L, countIn(schemaB, id));
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("Đặt tenant sau khi EntityManager đã mở cho hệ thống bị từ chối")
    void rejectLateTenant() {
        ProbeWorkspace a = newWorkspace("boundary-late");
        provisioner.provisionSchema(a.workspaceId());
        try (var context = new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            var tx = context.getBean(WorkspaceTransaction.class);
            var transactions =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThrows(
                    IllegalStateException.class,
                    () -> transactions.executeWithoutResult(_ -> tx.applyContext(a.workspaceId())));
        }
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName(
            "Reset search_path trong transaction lỗi phải loại connection để lần mượn sau sạch tenant")
    void discardFailedReset() throws Exception {
        String schema = provisioner.provisionSchema(newWorkspace("boundary-pool").workspaceId());
        HikariConfig config = poolConfig(1);
        config.setConnectionInitSql("SET ROLE giaokhoa_runtime");
        try (var pool = new HikariDataSource(config)) {
            var provider = new TenantConnectionProvider(pool);
            String original;
            try (Connection connection = provider.getAnyConnection()) {
                original = searchPath(connection);
            }
            try (Connection connection = provider.getConnection(schema)) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.execute("SELECT 1 / 0"));
                }
                assertThrows(
                        SQLException.class, () -> provider.releaseConnection(schema, connection));
            }
            try (Connection clean = provider.getConnection(TenantContext.SYSTEM_SCHEMA)) {
                assertEquals(original, searchPath(clean));
            }
        }
    }

    @Test
    @DisplayName("Scope tham gia transaction cũ khôi phục ThreadLocal cả trước lẫn sau commit")
    void restoreJoinedContext() {
        ProbeWorkspace a = newWorkspace("joined-context");
        var manager = new DataSourceTransactionManager(runtimeDataSource);
        var jdbc = new JdbcTemplate(runtimeDataSource);
        var tx = new WorkspaceTransaction(jdbc, manager);
        new TransactionTemplate(manager)
                .executeWithoutResult(_ -> {
                    tx.inWorkspace(a.workspaceId(), () -> null);
                    assertNull(TenantContext.current());
                });
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName(
            "Đọc tenant khác dùng EntityManager riêng và giữ nguyên ghi chờ flush cùng context của tenant đích")
    void isolateSourceRead() {
        ProbeWorkspace a = newWorkspace("read-a");
        ProbeWorkspace b = newWorkspace("read-b");
        String schemaA = provisioner.provisionSchema(a.workspaceId());
        String schemaB = provisioner.provisionSchema(b.workspaceId());
        UUID id = UUID.randomUUID();
        UUID rejectedWrite = UUID.randomUUID();
        try (var context = new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            var tx = context.getBean(WorkspaceTransaction.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            tx.inWorkspace(
                    a.workspaceId(),
                    () -> {
                        EntityManager em =
                                Objects.requireNonNull(
                                        EntityManagerFactoryUtils.getTransactionalEntityManager(
                                                context.getBean(EntityManagerFactory.class)));
                        em.persist(new ProbeSubscriptionPeriod(id, a.workspaceId(), a.userId()));
                        assertEquals(
                                schemaB,
                                tx.readInWorkspace(
                                        b.workspaceId(),
                                        () -> {
                                            EntityManager source =
                                                    Objects.requireNonNull(
                                                            EntityManagerFactoryUtils
                                                                    .getTransactionalEntityManager(
                                                                            context.getBean(
                                                                                    EntityManagerFactory
                                                                                            .class)));
                                            assertNotSame(em, source);
                                            return source.unwrap(Session.class)
                                                    .getTenantIdentifierValue();
                                        }));
                        var failure =
                                assertThrows(
                                        org.springframework.dao.DataAccessException.class,
                                        () ->
                                                tx.readInWorkspace(
                                                        b.workspaceId(),
                                                        () ->
                                                                jdbc.update(
                                                                        "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,confirmation_ref,confirmed_by,confirmed_at) VALUES (?,?,now(),now() + interval '1 hour',?,?,now())",
                                                                        rejectedWrite,
                                                                        b.workspaceId(),
                                                                        "readonly-" + rejectedWrite,
                                                                        b.userId())));
                        assertEquals(
                                "25006",
                                ((SQLException) failure.getMostSpecificCause()).getSQLState());
                        assertEquals(schemaA, TenantContext.currentSchema());
                        assertEquals(
                                schemaA,
                                jdbc.queryForObject("SELECT current_schema()", String.class));
                        assertEquals(
                                a.workspaceId(),
                                Objects.requireNonNull(TenantContext.current()).workspaceId());
                        em.flush();
                        return null;
                    });
        }
        assertEquals(1L, countIn(schemaA, id));
        assertEquals(0L, countIn(schemaB, id));
        assertEquals(0L, countIn(schemaB, rejectedWrite));
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName(
            "Copy giữa hai schema giữ nội dung, provenance và dependency nguồn qua runtime role")
    void copyAcrossSchemas() {
        ProbeWorkspace a = newWorkspace("copy-a");
        ProbeWorkspace b = newWorkspace("copy-b");
        String schemaA = provisioner.provisionSchema(a.workspaceId());
        String schemaB = provisioner.provisionSchema(b.workspaceId());
        UUID source = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UUID componentVersion = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        for (UUID resource : List.of(source, component)) {
            raw.update(
                    "INSERT INTO "
                            + schemaA
                            + ".resource(id,owner_workspace_id,created_by_user_id,kind,subject_id,state) VALUES (?,?,?,'QUESTION',?,'ACTIVE')",
                    resource,
                    a.workspaceId(),
                    a.userId(),
                    subject);
        }
        raw.update(
                "INSERT INTO "
                        + schemaA
                        + ".resource_version(id,resource_id,version_no,label,body_stub,answer_stub) VALUES (?,?,1,'Nguồn','Nội dung','Đáp án')",
                version,
                source);
        raw.update(
                "INSERT INTO "
                        + schemaA
                        + ".resource_version(id,resource_id,version_no,label,body_stub) VALUES (?,?,1,'Thành phần','Thành phần')",
                componentVersion,
                component);
        raw.update(
                "INSERT INTO "
                        + schemaA
                        + ".share_grant(id,source_resource_id,source_version_id,grantor_user_id,recipient_workspace_id,mode,state,actions,valid_from) VALUES (?,?,?,?,?,'REVOCABLE','OFFERED',ARRAY['use','share'],now())",
                grant,
                source,
                version,
                a.userId(),
                b.workspaceId());
        raw.update(
                "INSERT INTO "
                        + schemaA
                        + ".resource_dependency(id,derived_resource_id,derived_version_id,source_resource_id,source_version_id,share_grant_id,usage_constraints) VALUES (?,?,?,?,?,?,'component')",
                UUID.randomUUID(),
                source,
                version,
                component,
                componentVersion,
                grant);
        UUID copyResource = UUID.randomUUID();
        UUID copyVersion = UUID.randomUUID();
        try (var context = new AnnotationConfigApplicationContext(BridgeConfiguration.class)) {
            var tx = context.getBean(WorkspaceTransaction.class);
            var factory =
                    new JpaRepositoryFactory(
                            SharedEntityManagerCreator.createSharedEntityManager(
                                    context.getBean(EntityManagerFactory.class)));
            var copies =
                    new ResourceCopies(
                            factory.getRepository(ResourceRepository.class),
                            factory.getRepository(ResourceVersionRepository.class),
                            factory.getRepository(ResourceDependencyRepository.class),
                            tx);
            var metadata =
                    new VersionMetadata(
                            source,
                            version,
                            a.workspaceId(),
                            "PERSONAL",
                            a.userId(),
                            subject,
                            "QUESTION",
                            "DRAFT");
            tx.inWorkspace(
                    b.workspaceId(),
                    () -> {
                        assertEquals(
                                "giaokhoa_runtime",
                                context.getBean(JdbcTemplate.class)
                                        .queryForObject("SELECT current_user", String.class));
                        var data = copies.readVersion(metadata).orElseThrow();
                        return copies.copyVersion(
                                copyResource,
                                copyVersion,
                                a.userId(),
                                b.workspaceId(),
                                metadata,
                                data.label(),
                                data.body(),
                                data.answer(),
                                data.reviewState(),
                                data.lockedAt(),
                                1,
                                grant,
                                "origin=SHARE");
                    });
        }
        assertEquals(
                "Nội dung",
                raw.queryForObject(
                        "SELECT body_stub FROM " + schemaB + ".resource_version WHERE id=?",
                        String.class,
                        copyVersion));
        assertEquals(
                "Đáp án",
                raw.queryForObject(
                        "SELECT answer_stub FROM " + schemaB + ".resource_version WHERE id=?",
                        String.class,
                        copyVersion));
        assertEquals(
                2,
                raw.queryForObject(
                        "SELECT count(*) FROM "
                                + schemaB
                                + ".resource_dependency WHERE derived_version_id=?",
                        Integer.class,
                        copyVersion));
        assertEquals(
                List.of(source, component).stream().sorted().toList(),
                raw
                        .queryForList(
                                "SELECT source_resource_id FROM "
                                        + schemaB
                                        + ".resource_dependency WHERE derived_version_id=? ORDER BY source_resource_id",
                                UUID.class,
                                copyVersion)
                        .stream()
                        .sorted()
                        .toList());
        assertEquals(
                0,
                raw.queryForObject(
                        "SELECT count(*) FROM " + schemaA + ".resource WHERE id=?",
                        Integer.class,
                        copyResource));
        var invalid =
                assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () ->
                                raw.update(
                                        "INSERT INTO "
                                                + schemaB
                                                + ".resource_dependency(id,derived_resource_id,derived_version_id,source_resource_id,source_version_id,usage_constraints) VALUES (?,?,?,?,?,'invalid-derived')",
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        source,
                                        version));
        assertEquals("23503", ((SQLException) invalid.getMostSpecificCause()).getSQLState());
    }

    private ProbeWorkspace newWorkspace(String name) {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(migrationDataSource))
                .executeWithoutResult(status -> {
                    raw.update(
                            "INSERT INTO user_account (id, keycloak_sub, username, email,"
                                    + " email_verified, origin, identity_state) VALUES (?, ?,"
                                    + " ?, NULL, false, 'SELF_REGISTERED', 'ACTIVE')",
                            userId,
                            "sub-" + name + "-" + userId,
                            name + "-" + userId);
                    raw.update(
                            "INSERT INTO workspace (id, kind, owner_user_id, state) VALUES"
                                    + " (?, 'PERSONAL', ?, 'ACTIVE')",
                            workspaceId,
                            userId);
                });
        createdUsers.add(userId);
        createdWorkspaces.add(workspaceId);
        return new ProbeWorkspace(userId, workspaceId);
    }

    private static HikariConfig poolConfig(int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(databaseUrl);
        config.setUsername(env("TEST_DATABASE_USERNAME"));
        config.setPassword(env("TEST_DATABASE_PASSWORD"));
        config.setMaximumPoolSize(poolSize);
        return config;
    }

    private static long countViaJpa(
            SessionFactory sessionFactory, UUID workspaceId, String schema, UUID probeId) {
        TenantContext.set(workspaceId, schema);
        try (Session session = sessionFactory.openSession()) {
            return session.createQuery(
                            "select count(p) from ProbeSubscriptionPeriod p where p.id = :id",
                            Long.class)
                    .setParameter("id", probeId)
                    .getSingleResult();
        } finally {
            TenantContext.clear();
        }
    }

    private long countIn(String schema, UUID probeId) {
        Long count =
                raw.queryForObject(
                        "SELECT count(*) FROM " + schema + ".subscription_period WHERE id = ?",
                        Long.class,
                        probeId);
        return count == null ? -1L : count;
    }

    private static String searchPath(Connection connection) throws Exception {
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT current_setting('search_path')");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Không có dòng kết quả khi đọc search_path");
            }
            return result.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Không có dòng kết quả khi truy vấn");
            }
            return result.getLong(1);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + key);
        }
        return value;
    }

    /**
     * Entity probe ánh xạ bảng subscription_period có thật ở cả schema hệ thống và schema tenant.
     */
    @Entity(name = "ProbeSubscriptionPeriod")
    @Table(name = "subscription_period")
    public static class ProbeSubscriptionPeriod {

        @Id private UUID id;

        @Column(name = "workspace_id", nullable = false)
        private UUID workspaceId;

        @Column(name = "valid_from", nullable = false)
        private Instant validFrom;

        @Column(name = "expires_at", nullable = false)
        private Instant expiresAt;

        @Column(name = "confirmation_ref", nullable = false)
        private String confirmationRef;

        @Column(name = "confirmed_by", nullable = false)
        private UUID confirmedBy;

        @Column(name = "confirmed_at", nullable = false)
        private Instant confirmedAt;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @Column(nullable = false)
        private long version;

        protected ProbeSubscriptionPeriod() {}

        public ProbeSubscriptionPeriod(UUID id, UUID workspaceId, UUID confirmedBy) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.validFrom = Instant.now();
            this.expiresAt = Instant.now().plusSeconds(3600);
            this.confirmationRef = "probe-" + id.toString().substring(0, 8);
            this.confirmedBy = confirmedBy;
            this.confirmedAt = Instant.now();
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
            this.version = 0;
        }

        public UUID getId() {
            return id;
        }
    }

    /** Cấu hình Spring tối thiểu cho kịch bản cầu nối: EMF dùng provider/resolver tenancy thật. */
    @Configuration
    static class BridgeConfiguration {

        @Bean
        HikariDataSource dataSource() {
            HikariConfig config = poolConfig(2);
            config.setConnectionInitSql("SET ROLE giaokhoa_runtime");
            return new HikariDataSource(config);
        }

        @Bean
        TenantConnectionProvider tenantConnectionProvider(DataSource dataSource) {
            return new TenantConnectionProvider(dataSource);
        }

        @Bean
        WorkspaceTenantResolver tenantResolver() {
            return new WorkspaceTenantResolver();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource dataSource,
                TenantConnectionProvider provider,
                WorkspaceTenantResolver resolver) {
            LocalContainerEntityManagerFactoryBean emf =
                    new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setPackagesToScan(
                    "com.giaokhoa.infrastructure.tenancy", "com.giaokhoa.resources.model");
            Map<String, Object> props = new HashMap<>();
            props.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, provider);
            props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
            props.put("hibernate.hbm2ddl.auto", "none");
            emf.setJpaPropertyMap(props);
            return emf;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        WorkspaceTransaction workspaceTransaction(
                JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
            return new WorkspaceTransaction(jdbc, transactionManager);
        }
    }
}
