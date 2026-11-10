package com.giaokhoa.infrastructure.tenancy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Áp dòng migration tenant cho mọi schema đã cấp (fan-out khi phát hành và đường xử lý lại các
 * schema cấp lỗi). Tiến độ được trả theo từng schema: một schema lỗi bị cách ly trong kết quả,
 * không chặn các schema còn lại.
 */
@Component
public class TenantMigrationRunner {

    /** Kết quả fan-out cho một schema; {@code error} chỉ có khi {@code success} là false. */
    public record Result(
            UUID workspaceId, String schemaName, boolean success, @Nullable String error) {}

    private final JdbcTemplate jdbc;
    private final TenantSchemaProvisioner provisioner;

    public TenantMigrationRunner(JdbcTemplate jdbc, TenantSchemaProvisioner provisioner) {
        this.jdbc = jdbc;
        this.provisioner = provisioner;
    }

    public List<Result> migrateAllTenants() {
        List<UUID> workspaceIds =
                jdbc.queryForList(
                        "SELECT id FROM workspace WHERE schema_name IS NOT NULL"
                                + " ORDER BY created_at, id",
                        UUID.class);
        return workspaceIds.stream()
                .map(workspaceId -> {
                    String schema = TenantContext.schemaNameFor(workspaceId);
                    try {
                        provisioner.provisionSchema(workspaceId);
                        return new Result(workspaceId, schema, true, null);
                    } catch (RuntimeException e) {
                        return new Result(workspaceId, schema, false, e.getMessage());
                    }
                })
                .toList();
    }
}
