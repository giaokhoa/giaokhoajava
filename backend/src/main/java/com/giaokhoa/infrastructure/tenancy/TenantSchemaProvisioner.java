package com.giaokhoa.infrastructure.tenancy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cấp schema riêng cho một workspace: tạo schema, chạy dòng migration tenant ({@code
 * db/migration-tenant}) bằng Flyway với bảng history nằm trong chính schema đó — mẫu "nhiều schema
 * giống hệt, mỗi tenant một schema" của tài liệu Flyway — cấp quyền cho runtime role, rồi ghi tên
 * schema vào registry {@code workspace.schema_name}. Workspace chỉ được coi là sẵn sàng ở mô hình
 * mới sau khi bước ghi registry này hoàn tất. Mọi bước đều idempotent: gọi lại trên workspace đã
 * cấp chỉ áp migration tenant còn thiếu.
 */
@Component
public class TenantSchemaProvisioner {
    private static final Logger LOG = LoggerFactory.getLogger(TenantSchemaProvisioner.class);
    private static final String TENANT_LOCATIONS = "classpath:db/migration-tenant";

    private final DataSource migrationDataSource;
    private final JdbcTemplate migrationJdbc;
    private final boolean provisioningEnabled;

    /**
     * Dựng kết nối migration nội bộ từ credential của dòng Flyway hệ thống ({@code
     * spring.flyway.user}) trên cùng database: cấp schema là thao tác của mặt phẳng migration,
     * không đi qua datasource runtime bị giới hạn bởi runtime role. Kết nối này cố ý KHÔNG phải là
     * một bean {@code DataSource}: chỉ cần tồn tại một bean kiểu đó là Spring Boot tắt hẳn tự cấu
     * hình DataSource chính (@ConditionalOnMissingBean trong DataSourceAutoConfiguration) và toàn
     * bộ runtime rơi về kết nối migration không pool, chạy bằng role owner — RLS bị bypass.
     */
    @Autowired
    public TenantSchemaProvisioner(Environment environment) {
        this(
                migrationDataSource(environment),
                environment.getProperty(
                        "giaokhoa.tenancy.provisioning-enabled", Boolean.class, true));
    }

    /**
     * Constructor cho kiểm thử/harness: dùng thẳng kết nối migration cho trước, provisioning bật.
     */
    public TenantSchemaProvisioner(DataSource migrationDataSource) {
        this(migrationDataSource, true);
    }

    private TenantSchemaProvisioner(DataSource migrationDataSource, boolean provisioningEnabled) {
        this.migrationDataSource = migrationDataSource;
        this.migrationJdbc = new JdbcTemplate(migrationDataSource);
        this.provisioningEnabled = provisioningEnabled;
    }

    private static DataSource migrationDataSource(Environment environment) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getProperty("spring.flyway.user"));
        dataSource.setPassword(environment.getProperty("spring.flyway.password"));
        return dataSource;
    }

    /** Cấp (hoặc bổ sung migration cho) schema của workspace; trả về tên schema. */
    public String provisionSchema(UUID workspaceId) {
        String schema = TenantContext.schemaNameFor(workspaceId);
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations(TENANT_LOCATIONS)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        grantRuntimeRole(schema);
        int updated =
                migrationJdbc.update(
                        "UPDATE workspace SET schema_name = ? WHERE id = ?", schema, workspaceId);
        if (updated == 0) {
            throw new IllegalStateException(
                    "Không ghi được schema_name: workspace không tồn tại: " + workspaceId);
        }
        return schema;
    }

    /**
     * Lên lịch cấp schema ngay sau khi transaction tạo workspace commit. Cấp schema chạy bằng kết
     * nối migration riêng nên không thể nằm trong transaction nghiệp vụ; lỗi khi cấp được ghi log
     * và workspace ở lại trạng thái chưa có schema (phân giải về schema hệ thống như cũ) để {@link
     * TenantMigrationRunner} xử lý lại, thay vì làm hỏng yêu cầu tạo workspace đã commit. Công tắc
     * {@code giaokhoa.tenancy.provisioning-enabled=false} tắt hẳn bước này: workspace mới ở mô hình
     * cũ trên schema hệ thống như trước đợt di trú.
     */
    public void provisionAfterCommit(UUID workspaceId) {
        if (!provisioningEnabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            provisionQuietly(workspaceId);
                        }
                    });
        } else {
            provisionQuietly(workspaceId);
        }
    }

    private void provisionQuietly(UUID workspaceId) {
        try {
            provisionSchema(workspaceId);
        } catch (RuntimeException e) {
            LOG.error("Không cấp được schema tenant cho workspace {}", workspaceId, e);
        }
    }

    private void grantRuntimeRole(String schema) {
        // Tên schema suy ra tất định từ UUID nên an toàn để nội suy vào format(); role có thể
        // chưa tồn tại ở môi trường chưa chạy migration hệ thống V027 nên phải kiểm trước.
        migrationJdbc.execute(
                "DO $$ BEGIN"
                        + " IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'giaokhoa_runtime') THEN"
                        + " EXECUTE format('GRANT USAGE ON SCHEMA %I TO giaokhoa_runtime', '"
                        + schema
                        + "');"
                        + " EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE"
                        + " ON ALL TABLES IN SCHEMA %I TO giaokhoa_runtime', '"
                        + schema
                        + "');"
                        + " EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES"
                        + " IN SCHEMA %I TO giaokhoa_runtime', '"
                        + schema
                        + "');"
                        // Default privileges gắn với role migration đang chạy Flyway của dòng
                        // tenant: bảng/sequence do các migration tenant sau này tạo trong schema
                        // (fan-out phát hành) tự được grant, không phụ thuộc grant chạy lại.
                        + " EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I"
                        + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES"
                        + " TO giaokhoa_runtime', '"
                        + schema
                        + "');"
                        + " EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I"
                        + " GRANT USAGE, SELECT ON SEQUENCES TO giaokhoa_runtime', '"
                        + schema
                        + "');"
                        + " END IF; END $$");
    }
}
