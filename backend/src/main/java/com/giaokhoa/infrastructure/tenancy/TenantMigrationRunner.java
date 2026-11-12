package com.giaokhoa.infrastructure.tenancy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Áp dòng migration tenant cho mọi workspace (fan-out khi phát hành và đường xử lý lại). Workspace
 * đã cấp schema được áp migration còn thiếu; workspace chưa kích hoạt chỉ được CHUẨN BỊ, KHÔNG
 * publish registry. Lúc chưa chuyển dữ kiện, việc publish sẽ khiến request đi sang schema rỗng và
 * nhìn như mất dữ liệu. Cutover được làm ở bước riêng, có kiểm chứng và khóa workspace. Tiến độ
 * được trả theo từng workspace: một workspace lỗi bị cách ly trong kết quả, không chặn các
 * workspace còn lại.
 */
@Component
public class TenantMigrationRunner {

    /** Kết quả fan-out cho một workspace; {@code error} chỉ có khi {@code success} là false. */
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
                jdbc.queryForList("SELECT id FROM workspace ORDER BY created_at, id", UUID.class);
        return workspaceIds.stream()
                .map(workspaceId -> {
                    String schema = TenantContext.schemaNameFor(workspaceId);
                    try {
                        provisioner.prepareSchema(workspaceId);
                        return new Result(workspaceId, schema, true, null);
                    } catch (FlywayException | DataAccessException | IllegalStateException e) {
                        // Cách ly đúng các lỗi vận hành đã biết của đường cấp schema; lỗi lập
                        // trình khác vẫn nổi lên thay vì bị ghi thành kết quả từng workspace.
                        return new Result(workspaceId, schema, false, e.getMessage());
                    }
                })
                .toList();
    }
}
