package com.giaokhoa.infrastructure.tenancy;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
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
 * {@code search_path} được khôi phục về đúng giá trị pool đã cấu hình lúc mượn — connection mang
 * schema của tenant trước rò sang request sau là lỗi cách ly nghiêm trọng nhất của mô hình này, còn
 * khôi phục bằng RESET về mặc định của role sẽ xoá mất cấu hình search_path mà pool đặt qua
 * connection-init-sql trên chính connection vật lý đó. Connection cho tenant hệ thống và connection
 * "any" (khởi động/metadata) giữ nguyên search_path của pool: schema hệ thống vật lý do cấu hình
 * datasource quyết định (là {@code public} ở deployment chuẩn, là schema riêng ở các môi trường
 * kiểm thử cô lập chạy toàn bộ ứng dụng trong một schema khác), không ép về một tên cố định ở đây.
 */
@Component
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {
    private static final Logger LOG = LoggerFactory.getLogger(TenantConnectionProvider.class);

    @Serial private static final long serialVersionUID = 1L;

    // transient: DataSource không Serializable còn interface SPI của Hibernate kế thừa
    // Serializable; bean singleton này không bao giờ được serialize trong ứng dụng.
    private final transient DataSource dataSource;

    /**
     * Search_path gốc (do pool cấu hình) của từng connection đang được mượn cho một tenant, để khôi
     * phục đúng giá trị đó khi trả connection. WeakHashMap: mục của connection không được trả theo
     * đường này (đóng đột ngột, pool loại bỏ) tự hết hạn theo connection.
     */
    private transient Map<Connection, String> borrowedPaths = newBorrowedPaths();

    public TenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static Map<Connection, String> newBorrowedPaths() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    // Bean singleton không bao giờ được serialize trong ứng dụng, nhưng interface SPI của
    // Hibernate kế thừa Serializable nên trường transient phải có đường dựng lại khi khử
    // serialize, nếu không borrowedPaths sẽ null ở bản khử serialize.
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        borrowedPaths = newBorrowedPaths();
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
            borrowedPaths.put(connection, currentPath);
            statement.setString(1, schema + ", " + currentPath);
            statement.execute();
        } catch (SQLException e) {
            borrowedPaths.remove(connection);
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection)
            throws SQLException {
        String originalPath = borrowedPaths.remove(connection);
        if (originalPath != null) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
                statement.setString(1, originalPath);
                statement.execute();
            } catch (SQLException e) {
                // shortcut: khôi phục thất bại chỉ xảy ra khi connection đã hỏng; khi đó pool
                // tự loại connection ở lần mượn sau. Ghi log để vận hành thấy thay vì nuốt lỗi.
                LOG.warn(
                        "Không khôi phục được search_path khi trả connection của tenant {}",
                        tenantIdentifier,
                        e);
            }
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
