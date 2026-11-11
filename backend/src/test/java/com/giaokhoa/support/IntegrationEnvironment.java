package com.giaokhoa.support;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Lớp base cho các integration test opt-in chạy trên Compose TEST stack cục bộ tách biệt. Không bao
 * giờ chạy chúng trên cơ sở dữ liệu hay realm DEV.
 */
@ActiveProfiles("integration")
public abstract class IntegrationEnvironment { // NOPMD - Lớp base TEST để kế thừa.
    // shortcut: Fixture này chỉ nhắm Compose TEST cục bộ; TEST bên ngoài cần một allowlist
    // được kiểm chứng riêng.
    static final String TEST_DB = "jdbc:postgresql://127.0.0.1:15432/giaokhoa_test";
    static final String TEST_ISSUER = "http://127.0.0.1:18081/realms/giaokhoa-it";
    // Làn 2: stack TEST chuyên dụng thứ hai đã kiểm chứng (cùng realm/credential như làn 1,
    // chỉ khác cổng). DB và issuer phải đi cùng làn — cặp chéo vẫn bị từ chối.
    static final String TEST_DB_LANE_2 = "jdbc:postgresql://127.0.0.1:15433/giaokhoa_test";
    private static final String OPERATION_FAILED = "FAILED";
    static final String TEST_ISSUER_LANE_2 = "http://127.0.0.1:18082/realms/giaokhoa-it";
    // Redirect URI callback dùng chung của các fixture Code+PKCE: khai báo duy nhất ở đây.
    // Client TEST đăng ký đúng URI này ở test-realm.json (tầng định nghĩa, giữ nguyên tại đó).
    public static final String TEST_REDIRECT_URI = "http://127.0.0.1:18888/callback";
    // Redirect URI callback của native gate; client native đăng ký ở install-native-gate.sh.
    public static final String NATIVE_REDIRECT_URI = "http://127.0.0.1:18895/callback";
    // Schema mặc định của kết nối TEST — schema dùng chung cho toàn bộ nhóm IT full-context:
    // các lớp kiểm thử không còn khai schema riêng, context được Spring cache và dùng lại giữa
    // các class cùng cấu hình; cô lập nằm ở dữ kiện, xem truncateSharedSchemaData.
    private static final String SHARED_SCHEMA = "public";

    @DynamicPropertySource
    static void testServices(DynamicPropertyRegistry registry) {
        String database = testDatabaseUrl();
        String issuer = testIssuerUri();

        registry.add("spring.datasource.url", () -> database);
        registry.add("spring.datasource.username", () -> required("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("TEST_DATABASE_PASSWORD"));
        registry.add("spring.flyway.user", () -> required("TEST_DATABASE_MIGRATOR_USERNAME"));
        registry.add("spring.flyway.password", () -> required("TEST_DATABASE_MIGRATOR_PASSWORD"));
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", () -> issuer);
        registry.add(
                "spring.security.oauth2.client.registration.keycloak.client-id",
                () -> required("TEST_OIDC_CLIENT_ID"));
        registry.add(
                "spring.security.oauth2.client.registration.keycloak.client-secret",
                () -> required("TEST_OIDC_CLIENT_SECRET"));
    }

    /** Guard dùng chung cả khi test JDBC không nạp Spring context. */
    protected static String testDatabaseUrl() {
        String database = required("TEST_DATABASE_URL");
        validateTargets(database, testIssuerUri());
        return database;
    }

    public static String testIssuerUri() {
        return required("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "")
                + "/realms/"
                + required("TEST_KEYCLOAK_REALM");
    }

    /**
     * Cô lập dữ kiện giữa các lớp kiểm thử dùng chung một context và một schema (một context dùng
     * chung theo cơ chế cache của Spring TestContext): trước mỗi class, xoá toàn bộ dữ kiện còn lại
     * của class trước trong schema dùng chung. Mỗi class vẫn bắt đầu từ bảng rỗng như khi còn
     * schema riêng, nhưng ApplicationContext và schema đã migrate được dùng lại thay vì nạp và
     * migrate lại theo từng class. Chạy bằng connection độc lập vì ở thời điểm {@code @BeforeAll}
     * context có thể chưa được nạp. Trên DB trắng chưa migrate lần nào thì chưa có bảng nào để xoá
     * nên bước này tự bỏ qua.
     *
     * <p>Các ngoại lệ không truncate như dữ kiện thường: {@code flyway_schema_history} (lịch sử
     * migrate, là một phần của schema đã migrate) và {@code subject} — baseline hệ thống gieo đúng
     * ba môn M1/M2/M3 mà test giảng dạy đọc trực tiếp qua danh mục, nên bảng này chỉ xoá các môn
     * ngoài ba mã gốc (môn do test tự thêm), giữ nguyên ba dòng gieo. Migration nào gieo thêm bảng
     * gốc mới thì phải xử lý tương tự ở bước dọn này.
     *
     * <p>Ngoại lệ nữa: hai dòng singleton của target {@code platform} trong {@code
     * authz_partition}/{@code authz_projection} — {@code PlatformProjectionSeeder} chỉ tạo chúng
     * một lần lúc context khởi động (sự kiện {@code ApplicationReadyEvent}) nên context dùng chung
     * không tạo lại được sau khi xoá. Hai bảng này chỉ xoá các dòng không thuộc platform.
     *
     * <p>Connection dọn dẹp đặt {@code session_replication_role = 'replica'}: lịch sử audit của ứng
     * dụng là append-only (trigger {@code reject_audit_event_mutation} chặn cả DELETE/TRUNCATE lan
     * qua khóa ngoại), nên dọn liên class phải tắt trigger trên đúng phiên dọn dẹp này. Mọi test
     * thật vẫn chạy ở role {@code origin} nên bất biến append-only vẫn được khóa nguyên vẹn (xem
     * {@code AuditEventSchemaIT}); đây là thao tác của harness trên DB TEST, giữa hai class, không
     * phải đường dữ kiện của test. Connection dọn dẹp dùng credential migration (chủ sở hữu đối
     * tượng, có quyền đặt session_replication_role) vì datasource ứng dụng của suite chạy bằng role
     * runtime không có quyền đó.
     */
    @BeforeAll
    static void truncateSharedSchemaData() {
        // Context dùng chung vẫn sống trong lúc dọn (worker/scheduler của nó không được dừng
        // như teardown schema riêng trước đây), nên lượt dọn vẫn có thể chạm xung đột khoá với
        // một giao dịch ứng dụng đang chạy dở ở biên class: thử lại có giới hạn, mỗi lượt một
        // connection mới, chỉ với hai SQLState của xung đột khoá (40P01 deadlock_detected,
        // 55P03 lock_not_available). Các DELETE đều idempotent nên thử lại an toàn.
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                deleteSharedSchemaDataOnce();
                return;
            } catch (SQLException ex) {
                lastFailure = ex;
                if (!"40P01".equals(ex.getSQLState()) && !"55P03".equals(ex.getSQLState())) {
                    break;
                }
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException(
                "Không xoá được dữ kiện của schema dùng chung", lastFailure);
    }

    private static void deleteSharedSchemaDataOnce() throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                required("TEST_DATABASE_URL"),
                                required("TEST_DATABASE_MIGRATOR_USERNAME"),
                                required("TEST_DATABASE_MIGRATOR_PASSWORD"));
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = 'replica'");
            statement.execute("SET lock_timeout = '5s'");
            try {
                // DELETE thay cho TRUNCATE: RowExclusiveLock tương thích với khoá đọc
                // (AccessShareLock) của các giao dịch ứng dụng đang chạy trong context dùng
                // chung, nên không tạo deadlock ở biên class như AccessExclusiveLock của
                // TRUNCATE. Replica role đã tắt trigger khoá ngoại nên thứ tự xoá giữa các
                // bảng không quan trọng. Câu SQL là hằng: vòng lặp và ghép tên bảng chạy ngay
                // trong PostgreSQL bằng quote_ident, phía Java không ghép định danh vào SQL.
                statement.execute(
                        "DO $$ DECLARE r RECORD; BEGIN"
                                + " FOR r IN SELECT tablename FROM pg_tables"
                                + " WHERE schemaname = '"
                                + SHARED_SCHEMA
                                + "' AND tablename NOT IN ('flyway_schema_history', 'subject',"
                                + " 'authz_partition', 'authz_projection') LOOP"
                                + " EXECUTE 'DELETE FROM ' || quote_ident(r.tablename);"
                                + " END LOOP;"
                                + " FOR r IN SELECT schemaname, sequencename FROM pg_sequences"
                                + " WHERE schemaname = '"
                                + SHARED_SCHEMA
                                + "' LOOP"
                                + " EXECUTE 'ALTER SEQUENCE ' || quote_ident(r.schemaname)"
                                + " || '.' || quote_ident(r.sequencename) || ' RESTART';"
                                + " END LOOP; END $$");
                // Giữ đúng ba môn gốc M1/M2/M3 của baseline, chỉ xoá môn ngoài danh mục gốc;
                // chạy sau vòng xoá chính nên không còn khoá ngoại nào trỏ tới các môn bị xoá.
                // Guard to_regclass cho trường hợp DB chưa migrate lần nào (bảng chưa tồn tại).
                statement.execute(
                        "DO $$ BEGIN"
                                + " IF to_regclass('"
                                + SHARED_SCHEMA
                                + ".subject') IS NOT NULL THEN"
                                + " DELETE FROM subject WHERE code NOT IN ('M1', 'M2', 'M3');"
                                + " END IF; END $$");
                // Hai bảng authz chỉ xoá dòng không thuộc singleton platform (xem javadoc ở
                // trên): projection trước, partition sau để không vướng khoá ngoại giữa chúng.
                statement.execute(
                        "DO $$ BEGIN"
                                + " IF to_regclass('"
                                + SHARED_SCHEMA
                                + ".authz_projection') IS NOT NULL THEN"
                                + " DELETE FROM authz_projection WHERE target_key <> 'platform';"
                                + " END IF;"
                                + " IF to_regclass('"
                                + SHARED_SCHEMA
                                + ".authz_partition') IS NOT NULL THEN"
                                + " DELETE FROM authz_partition WHERE kind <> 'PLATFORM';"
                                + " END IF; END $$");
            } finally {
                statement.execute("SET session_replication_role = 'origin'");
            }
        }
    }

    /**
     * JdbcTemplate trên credential migration (chủ sở hữu đối tượng của stack TEST) cho các thao tác
     * hạ tầng mà role runtime của datasource ứng dụng không được phép làm: tạo/xoá role probe, cấp
     * quyền cho role probe, xoá schema cô lập. Đường dữ kiện nghiệp vụ của test vẫn chạy qua
     * datasource ứng dụng (role runtime) để toàn bộ suite chịu cùng kỷ luật quyền như runtime thật.
     */
    protected static JdbcTemplate migratorJdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        testDatabaseUrl(),
                        required("TEST_DATABASE_MIGRATOR_USERNAME"),
                        required("TEST_DATABASE_MIGRATOR_PASSWORD")));
    }

    /**
     * Khai báo schema cô lập cho class thật sự cần schema riêng: Flyway migrate vào đúng schema đó
     * và mọi kết nối của pool mang search_path trỏ vào nó. Hiện chỉ ProjectionRecoveryIT dùng (lý
     * do ghi tại khai báo schema của class đó); mọi class còn lại chạy trên schema dùng chung và cô
     * lập bằng dữ kiện (xem {@link #truncateSharedSchemaData()}).
     */
    protected static void isolatedSchema(DynamicPropertyRegistry registry, String schema) {
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + schema + ",public");
    }

    /**
     * Chờ operation do worker thật xử lý tới SUCCEEDED bằng Awaitility (poll 500 ms, tối đa 90
     * giây) — bản dùng chung thay hai bản sao viết tay trước đây ở IdentityFlowE2EIT và
     * IdentityEligibilityServiceIT. FAILED dừng chờ ngay và báo kèm mã lỗi; lỗi truy vấn trạng thái
     * được ném lại nguyên vẹn, đúng hành vi vòng poll cũ.
     */
    protected static void awaitOperationSucceeded(JdbcTemplate jdbc, UUID operationId) {
        AtomicReference<Object> failureCode = new AtomicReference<>();
        AtomicReference<DataAccessException> queryFailure = new AtomicReference<>();
        try {
            await().atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        try {
                            Map<String, Object> row =
                                    jdbc.queryForMap(
                                            "SELECT state, last_error_code FROM authorization_operation WHERE id = ?",
                                            operationId);
                            if (OPERATION_FAILED.equals(String.valueOf(row.get("state")))) {
                                failureCode.set(row.get("last_error_code"));
                                return true;
                            }
                            return "SUCCEEDED".equals(String.valueOf(row.get("state")));
                        } catch (DataAccessException ex) {
                            queryFailure.set(ex);
                            return true;
                        }
                    });
        } catch (ConditionTimeoutException ex) {
            fail("Operation did not succeed in time: " + operationId);
        }
        if (queryFailure.get() != null) {
            throw queryFailure.get();
        }
        if (failureCode.get() != null) {
            fail("Operation failed with " + failureCode.get());
        }
    }

    /** Chỉ thử lại fence chưa sẵn sàng; lỗi danh tính/dịch vụ khác vẫn được trả cho assertion. */
    protected static org.springframework.mock.web.MockHttpServletResponse requestWhenReady(
            org.springframework.test.web.servlet.MockMvc mvc,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        AtomicReference<org.springframework.mock.web.MockHttpServletResponse> last =
                new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> {
                    var response = mvc.perform(request).andReturn().getResponse();
                    last.set(response);
                    return response.getStatus() != 503
                            || !response.getContentAsString().contains("AUTHORIZATION_NOT_READY");
                });
        return java.util.Objects.requireNonNull(last.get());
    }

    /**
     * Xoá user Keycloak cuối test, thử lại khi gặp 409: một thao tác khác (worker chiếu group
     * membership) có thể đang chạm cùng user trong lúc chạy song song. 404 nghĩa là user đã không
     * còn nên coi như dọn xong; 409 dai dẳng hoặc lỗi khác vẫn ném kèm nội dung Keycloak trả về để
     * lần sau chẩn đoán được, thay vì chỉ còn mã trạng thái.
     */
    protected static void deleteWithConflictRetry(Runnable deleteOnce) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                deleteOnce.run();
                return;
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return;
                }
                if (ex.getStatusCode().value() != HttpStatus.CONFLICT.value() || attempt == 2) {
                    String body = ex.getResponseBodyAsString();
                    throw new IllegalStateException(
                            "Keycloak test admin DELETE: HTTP "
                                    + ex.getStatusCode().value()
                                    + (body == null || body.isBlank() ? "" : " — " + body),
                            ex);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while retrying Keycloak user cleanup");
                }
            }
        }
    }

    public static void validateTargets(String database, String issuer) {
        boolean lane1 = TEST_DB.equals(database) && TEST_ISSUER.equals(issuer);
        boolean lane2 = TEST_DB_LANE_2.equals(database) && TEST_ISSUER_LANE_2.equals(issuer);
        if (!lane1 && !lane2) {
            throw new IllegalArgumentException(
                    "Integration tests require the dedicated local TEST database and issuer");
        }
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing integration environment variable: " + key);
        }
        return value;
    }
}
