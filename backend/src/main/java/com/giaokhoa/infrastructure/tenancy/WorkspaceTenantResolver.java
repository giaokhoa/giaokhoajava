package com.giaokhoa.infrastructure.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Resolver tenant của Hibernate (chiến lược separate schema, Hibernate User Guide §24.3.3): tenant
 * identifier là tên schema lấy từ {@link TenantContext} của luồng hiện tại. Bật {@code
 * validateExistingCurrentSessions} để một session đã mở cho tenant khác bị từ chối thay vì âm thầm
 * phục vụ nhầm dữ liệu.
 */
@Component
public class WorkspaceTenantResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.currentSchema();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
