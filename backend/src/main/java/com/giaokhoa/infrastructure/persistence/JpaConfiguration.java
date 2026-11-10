package com.giaokhoa.infrastructure.persistence;

import com.giaokhoa.infrastructure.tenancy.TenantConnectionProvider;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTenantResolver;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình JPA dùng chung. Điểm nối multi-tenant được đăng ký tại đây theo cách chuẩn của Spring
 * Boot ({@link HibernatePropertiesCustomizer}): Hibernate nhận instance của connection provider và
 * tenant resolver (Hibernate User Guide §24.3.2–24.3.3 cho phép đăng ký bằng instance qua hai
 * setting này). Cầu nối JPA↔JDBC trong giai đoạn chuyển tiếp dựa trên DataSource chính dùng chung:
 * Spring Boot truyền chính DataSource đó vào EntityManagerFactory (JpaBaseConfiguration gọi {@code
 * factoryBuilder.dataSource(this.dataSource)}), và JpaTransactionManager mặc định giữ connection
 * của transaction JPA theo DataSource đó trên thread hiện tại — nên JdbcTemplate trên cùng
 * DataSource lấy đúng connection đang bị transaction giữ và dùng chung một transaction vật lý với
 * JPA.
 */
@Configuration
public class JpaConfiguration {

    @Bean
    public HibernatePropertiesCustomizer multiTenancyCustomizer(
            WorkspaceTenantResolver tenantResolver, TenantConnectionProvider connectionProvider) {
        return properties -> {
            properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
        };
    }
}
