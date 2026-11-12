package com.giaokhoa.infrastructure.tenancy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cấp schema riêng cho một workspace: tạo schema, chạy dòng migration tenant ({@code
 * db/migration-tenant}) bằng Flyway với bảng history nằm trong chính schema đó — mẫu "nhiều schema
 * giống hệt, mỗi tenant một schema" của tài liệu Flyway — và cấp quyền cho runtime role. Hai mức
 * hoàn tất phân biệt rõ: <b>chuẩn bị</b> ({@link #prepareSchema}) dừng ở đó; <b>kích hoạt</b>
 * ({@link #provisionSchema}) ghi thêm tên schema vào registry {@code workspace.schema_name}, là
 * điểm từ đó mọi context tenant trỏ vào schema riêng. Workspace chỉ được coi là sẵn sàng ở mô hình
 * mới sau bước ghi registry này. Mọi bước đều idempotent: gọi lại trên workspace đã cấp chỉ áp
 * migration tenant còn thiếu.
 */
@Component
public class TenantSchemaProvisioner {
    private static final Logger LOG = LoggerFactory.getLogger(TenantSchemaProvisioner.class);
    private static final String TENANT_LOCATIONS = "classpath:db/migration-tenant";
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final List<String> SCOPED_COLUMNS =
            List.of(
                    "workspace_id",
                    "owner_workspace_id",
                    "recipient_workspace_id",
                    "personal_workspace_id");

    private final DataSource migrationDataSource;
    private final JdbcTemplate migrationJdbc;
    private final boolean provisioningEnabled;

    /**
     * Dựng kết nối migration nội bộ từ credential của dòng Flyway hệ thống ({@code
     * spring.flyway.user}) trên cùng database: cấp schema là thao tác của mặt phẳng migration,
     * không đi qua datasource runtime bị giới hạn bởi runtime role. Kết nối này cố ý KHÔNG phải là
     * một bean {@code DataSource}: chỉ cần tồn tại một bean kiểu đó là Spring Boot tắt hẳn tự cấu
     * hình DataSource chính (@ConditionalOnMissingBean trong DataSourceAutoConfiguration) và toàn
     * bộ runtime rơi về kết nối migration không pool, chạy bằng role owner — mọi giới hạn quyền
     * theo schema của role runtime đều mất hiệu lực.
     */
    @Autowired
    public TenantSchemaProvisioner(Environment environment) {
        this(
                buildMigrationDataSource(environment),
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

    private static DataSource buildMigrationDataSource(Environment environment) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getProperty("spring.flyway.user"));
        dataSource.setPassword(environment.getProperty("spring.flyway.password"));
        return dataSource;
    }

    /**
     * Chuẩn bị schema của workspace: tạo schema, áp dòng migration tenant và cấp quyền cho runtime
     * role, nhưng CHƯA ghi registry {@code workspace.schema_name} — mặt phẳng dữ kiện của workspace
     * vẫn ở schema hệ thống. Chuẩn bị tách khỏi kích hoạt vì các service hiện hành ghi dữ kiện
     * tenant của workspace vừa tạo trên schema hệ thống (ngoài context tenant); kích hoạt ngay lúc
     * tạo sẽ trỏ các luồng có context vào schema riêng còn rỗng trong khi dữ kiện nằm ở schema hệ
     * thống, làm hỏng chính các luồng đó. Module sở hữu dữ kiện kích hoạt bằng {@link
     * #provisionSchema} khi chuyển dữ kiện vào schema riêng.
     */
    public String prepareSchema(UUID workspaceId) {
        String schema = TenantContext.schemaNameFor(workspaceId);
        migrateAndGrant(schema);
        return schema;
    }

    /**
     * Kích hoạt schema riêng của workspace: chuẩn bị như {@link #prepareSchema} rồi ghi tên schema
     * vào registry {@code workspace.schema_name} CHỈ khi chưa có dữ liệu tenant ở schema hệ thống.
     * Workspace legacy có dữ liệu phải đi qua quy trình copy/check riêng; một lần chạy runner không
     * được bí mật chuyển search_path sang schema rỗng. Kiểm và publish khóa workspace cùng tx.
     */
    public String provisionSchema(UUID workspaceId) {
        String schema = prepareSchema(workspaceId);
        // Chỉ cấp cho workspace hoàn toàn rỗng, hoặc workspace đã cutover trước đó.
        // Chuyển dữ kiện legacy phải là bước migration riêng đã kiểm chứng; không publish
        // registry làm mọi request đổi schema khi dữ kiện còn trong public.
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(migrationDataSource));
        transaction.executeWithoutResult(_ -> {
            List<Map<String, Object>> rows =
                    migrationJdbc.queryForList(
                            "SELECT schema_name FROM public.workspace WHERE id = ? FOR UPDATE",
                            workspaceId);
            if (rows.isEmpty()) {
                throw new IllegalStateException("Workspace không tồn tại: " + workspaceId);
            }
            Object current = rows.getFirst().get("schema_name");
            if (current != null) {
                if (!schema.equals(current)) {
                    throw new IllegalStateException("Workspace đã gắn schema khác: " + workspaceId);
                }
                return;
            }
            requireLegacyWorkspaceEmpty(workspaceId, schema);
            migrationJdbc.update(
                    "UPDATE public.workspace SET schema_name = ? WHERE id = ?",
                    schema,
                    workspaceId);
        });
        return schema;
    }

    /**
     * Soát mọi bảng business có cột scope theo tenant baseline, kể cả bảng mới của Flyway. Không
     * chỉ xét class/membership: receipt, share grant và practice history riêng cũng là dữ liệu
     * không được biến mất khỏi ứng dụng khi đổi search_path.
     */
    private void requireLegacyWorkspaceEmpty(UUID workspaceId, String schema) {
        List<Map<String, Object>> columns =
                migrationJdbc.queryForList(
                        "SELECT c.table_name, c.column_name FROM information_schema.columns c"
                                + " WHERE c.table_schema = 'public' AND c.column_name IN"
                                + " ('workspace_id','owner_workspace_id','recipient_workspace_id',"
                                + " 'personal_workspace_id')"
                                + " AND EXISTS (SELECT 1 FROM information_schema.tables t"
                                + " WHERE t.table_schema = ? AND t.table_name = c.table_name)"
                                + " ORDER BY c.table_name, c.column_name",
                        schema);
        if (columns.isEmpty()) {
            throw new IllegalStateException("Tenant schema chưa có bảng nguồn để kiểm: " + schema);
        }
        for (Map<String, Object> column : columns) {
            String table = String.valueOf(column.get("table_name"));
            String field = String.valueOf(column.get("column_name"));
            if (!SQL_IDENTIFIER.matcher(table).matches() || !SCOPED_COLUMNS.contains(field)) {
                throw new IllegalStateException("Cột scope tenant không hợp lệ: " + table);
            }
            Boolean present =
                    migrationJdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM public."
                                    + table
                                    + " WHERE "
                                    + field
                                    + " = ? LIMIT 1)",
                            Boolean.class,
                            workspaceId);
            if (Boolean.TRUE.equals(present)) {
                throw new IllegalStateException(
                        "Không kích hoạt schema rỗng: workspace còn dữ liệu cũ tại public."
                                + table
                                + " ("
                                + workspaceId
                                + ")");
            }
        }
    }

    private void migrateAndGrant(String schema) {
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations(TENANT_LOCATIONS)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        grantRuntimeRole(schema);
    }

    /**
     * Lên lịch CHUẨN BỊ schema ngay sau khi transaction tạo workspace commit (xem {@link
     * #prepareSchema} về lý do bước này không kích hoạt). Cấp schema chạy bằng kết nối migration
     * riêng nên không thể nằm trong transaction nghiệp vụ; lỗi khi chuẩn bị được ghi log và
     * workspace ở lại trạng thái chưa có schema (phân giải về schema hệ thống như cũ) để {@link
     * TenantMigrationRunner} xử lý lại, thay vì làm hỏng yêu cầu tạo workspace đã commit. Công tắc
     * {@code giaokhoa.tenancy.provisioning-enabled=false} tắt hẳn bước này: workspace mới ở lại
     * trên schema hệ thống.
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
                            prepareQuietly(workspaceId);
                        }
                    });
        } else {
            prepareQuietly(workspaceId);
        }
    }

    private void prepareQuietly(UUID workspaceId) {
        try {
            prepareSchema(workspaceId);
        } catch (FlywayException | DataAccessException | IllegalStateException e) {
            // Chỉ nuốt các lỗi vận hành đã biết của đường chuẩn bị schema (migration, truy cập
            // dữ liệu); lỗi lập trình khác vẫn nổi lên để lộ ngay.
            LOG.error("Không chuẩn bị được schema tenant cho workspace {}", workspaceId, e);
        }
    }

    private void grantRuntimeRole(String schema) {
        // Tên schema suy ra tất định từ UUID nên an toàn để nội suy vào format(); role có thể
        // chưa tồn tại ở môi trường chưa chạy baseline hệ thống (nơi tạo role có điều kiện)
        // nên phải kiểm trước.
        migrationJdbc.execute(
                "DO $$ BEGIN"
                        + " IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'giaokhoa_runtime') THEN"
                        + executeFormat("GRANT USAGE ON SCHEMA %I TO giaokhoa_runtime", schema)
                        + executeFormat(
                                "GRANT SELECT, INSERT, UPDATE, DELETE"
                                        + " ON ALL TABLES IN SCHEMA %I TO giaokhoa_runtime",
                                schema)
                        + executeFormat(
                                "GRANT USAGE, SELECT ON ALL SEQUENCES"
                                        + " IN SCHEMA %I TO giaokhoa_runtime",
                                schema)
                        // Lịch sử migration của schema tenant chỉ cho runtime đọc (grant ALL
                        // TABLES ở trên đã cấp cả quyền ghi vì bảng history nằm cùng schema).
                        + executeFormat(
                                "REVOKE INSERT, UPDATE, DELETE"
                                        + " ON %I.flyway_schema_history FROM giaokhoa_runtime",
                                schema)
                        // Default privileges gắn với role migration đang chạy Flyway của dòng
                        // tenant: bảng/sequence do các migration tenant sau này tạo trong schema
                        // (fan-out phát hành) tự được grant, không phụ thuộc grant chạy lại.
                        + executeFormat(
                                "ALTER DEFAULT PRIVILEGES IN SCHEMA %I"
                                        + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES"
                                        + " TO giaokhoa_runtime",
                                schema)
                        + executeFormat(
                                "ALTER DEFAULT PRIVILEGES IN SCHEMA %I"
                                        + " GRANT USAGE, SELECT ON SEQUENCES TO giaokhoa_runtime",
                                schema)
                        + " END IF; END $$");
    }

    /** Một lệnh grant chạy qua format() của khối DO: thân lệnh chứa %I cho tên schema. */
    private static String executeFormat(String body, String schema) {
        return " EXECUTE format('" + body + "', '" + schema + "');";
    }
}
