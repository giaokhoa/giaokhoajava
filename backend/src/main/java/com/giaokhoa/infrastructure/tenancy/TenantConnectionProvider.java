package com.giaokhoa.infrastructure.tenancy;

import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Connection provider cho chiến lược separate schema (Hibernate User Guide §24.2.2, §24.3.2): một
 * pool dùng chung, mỗi connection được đặt {@code search_path} = schema tenant + phần search_path
 * pool đã cấu hình (schema hệ thống của deployment) trước khi dùng. Khi trả connection về pool,
 * {@code search_path} bị reset bắt buộc — connection mang schema của tenant trước rò sang request
 * sau là lỗi cách ly nghiêm trọng nhất của mô hình này. Connection cho tenant hệ thống và
 * connection "any" (khởi động/metadata) giữ nguyên search_path của pool: schema hệ thống vật lý do
 * cấu hình datasource quyết định (là {@code public} ở deployment chuẩn, là schema riêng ở các môi
 * trường kiểm thử cô lập chạy toàn bộ ứng dụng trong một schema khác), không ép về một tên cố định
 * ở đây.
 */
@Component
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {
    private static final Logger LOG = LoggerFactory.getLogger(TenantConnectionProvider.class);

    @Serial private static final long serialVersionUID = 1L;

    // transient: DataSource không Serializable còn interface SPI của Hibernate kế thừa
    // Serializable; bean singleton này không bao giờ được serialize trong ứng dụng.
    private final transient DataSource dataSource;

    public TenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        String schema = requireSchemaName(tenantIdentifier);
        Connection connection = dataSource.getConnection();
        if (TenantContext.SYSTEM_SCHEMA.equals(schema)) {
            return connection;
        }
        try (Statement read = connection.createStatement();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
            String currentPath;
            try (var result = read.executeQuery("SELECT current_setting('search_path')")) {
                if (!result.next()) {
                    throw new SQLException("Không đọc được search_path hiện tại của connection");
                }
                currentPath = result.getString(1);
            }
            statement.setString(1, schema + ", " + currentPath);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
        } catch (SQLException e) {
            // shortcut: reset thất bại chỉ xảy ra khi connection đã hỏng; khi đó pool tự loại
            // connection ở lần mượn sau. Ghi log để vận hành thấy thay vì nuốt lỗi.
            LOG.warn(
                    "Không reset được search_path khi trả connection của tenant {}",
                    tenantIdentifier,
                    e);
        }
        connection.close();
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (unwrapType.isInstance(this)) {
            return unwrapType.cast(this);
        }
        throw new UnknownUnwrapTypeException(unwrapType);
    }

    private static String requireSchemaName(String tenantIdentifier) {
        if (TenantContext.SYSTEM_SCHEMA.equals(tenantIdentifier)
                || tenantIdentifier.matches("t_[0-9a-f]{32}")) {
            return tenantIdentifier;
        }
        throw new IllegalArgumentException("Tenant identifier không phải tên schema hợp lệ");
    }
}
