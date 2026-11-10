package com.giaokhoa.infrastructure.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.giaokhoa.support.IntegrationEnvironment;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Khóa bất biến về DataSource chính trên context ứng dụng thật: nó phải là pool Hikari do Spring
 * Boot tự cấu hình từ credential runtime. Một bean DataSource phụ bất kỳ trong context (ví dụ bean
 * cho kết nối migration của provisioning) làm Boot tắt hẳn tự cấu hình này
 * (@ConditionalOnMissingBean trong DataSourceAutoConfiguration), khiến toàn bộ runtime chạy bằng
 * kết nối migration không pool và bằng role owner — RLS bị bypass. Kiểm chứng này tồn tại để lỗi đó
 * không quay lại lặng lẽ.
 */
@SpringBootTest
@DisplayName("DataSource chính của ứng dụng")
class ApplicationDataSourceIT extends IntegrationEnvironment {

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("DataSource chính là pool Hikari và kết nối bằng đúng username runtime")
    void primaryDataSourceIsPooledAndRuntime() throws Exception {
        assertInstanceOf(HikariDataSource.class, dataSource);
        String expectedUsername = System.getenv("TEST_DATABASE_USERNAME");
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(expectedUsername, connection.getMetaData().getUserName());
        }
    }
}
