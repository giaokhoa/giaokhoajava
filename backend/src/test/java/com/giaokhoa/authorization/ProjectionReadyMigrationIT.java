package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

// Chỉ kiểm chứng migration Flyway bằng JDBC thuần trên schema riêng tự tạo/dọn trong thân test;
// không dùng bean Spring nào nên không dựng ApplicationContext.
class ProjectionReadyMigrationIT {
    @Test
    void upgradeFencesLegacyNullHashesAndTheirDependentsWithoutOpeningUnverifiedRows() {
        String schema = "ready_hash_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url =
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"))
                        + "?currentSchema="
                        + schema;
        var source =
                new DriverManagerDataSource(
                        url,
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_PASSWORD")));
        var jdbc = new JdbcTemplate(source);
        var configuration =
                Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema);
        configuration.target("005").load().migrate();
        UUID bad = UUID.randomUUID(),
                dependent = UUID.randomUUID(),
                independent = UUID.randomUUID();
        try {
            new TransactionTemplate(new DataSourceTransactionManager(source))
                    .executeWithoutResult(_ -> {
                        for (UUID id : new UUID[] {bad, dependent, independent}) {
                            jdbc.update(
                                    "INSERT INTO authz_partition(id,kind,owner_key) VALUES (?,'GRANT',?)",
                                    id,
                                    "grant:" + id);
                        }
                        jdbc.update(
                                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,keycloak_resource_id,state)"
                                        + " VALUES (?,?,?,?,'legacy-resource','READY')",
                                UUID.randomUUID(),
                                bad,
                                "grant:" + bad,
                                "a".repeat(64));
                        jdbc.update(
                                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,applied_hash,"
                                        + "keycloak_resource_id,dependency_partition_ids,state)"
                                        + " VALUES (?,?,?,?,?,'dependent-resource',ARRAY[?]::uuid[],'READY')",
                                UUID.randomUUID(),
                                dependent,
                                "grant:" + dependent,
                                "a".repeat(64),
                                "a".repeat(64),
                                bad);
                        jdbc.update("UPDATE authz_partition SET state='READY'");
                    });
            configuration.target("latest").load().migrate();
            assertEquals(
                    "PENDING",
                    jdbc.queryForObject(
                            "SELECT state FROM authz_partition WHERE id=?", String.class, bad));
            assertEquals(
                    "PENDING",
                    jdbc.queryForObject(
                            "SELECT state FROM authz_partition WHERE id=?",
                            String.class,
                            dependent));
            assertEquals(
                    "READY",
                    jdbc.queryForObject(
                            "SELECT state FROM authz_partition WHERE id=?",
                            String.class,
                            independent));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM authz_projection WHERE state='READY'",
                            Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            jdbc.update(
                                    "UPDATE authz_projection SET state='READY' WHERE partition_id=?",
                                    bad));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
