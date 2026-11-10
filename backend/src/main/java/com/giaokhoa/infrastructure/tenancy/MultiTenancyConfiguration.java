package com.giaokhoa.infrastructure.tenancy;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Cấu hình hạ tầng multi-tenant. Kết nối migration cho provisioning/fan-out dùng đúng credential
 * của dòng Flyway hệ thống ({@code spring.flyway.user}) trên cùng database: cấp schema là thao tác
 * của mặt phẳng migration, không đi qua datasource runtime bị giới hạn bởi runtime role.
 */
@Configuration
public class MultiTenancyConfiguration {

    @Bean("tenantMigrationDataSource")
    public DataSource tenantMigrationDataSource(Environment environment) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getProperty("spring.flyway.user"));
        dataSource.setPassword(environment.getProperty("spring.flyway.password"));
        return dataSource;
    }
}
