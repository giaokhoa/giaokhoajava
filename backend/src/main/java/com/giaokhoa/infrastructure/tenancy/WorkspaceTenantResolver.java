package com.giaokhoa.infrastructure.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Resolver tenant của Hibernate (chiến lược separate schema, Hibernate User Guide §24.3.3): tenant
 * identifier là tên schema lấy từ {@link TenantContext} trước khi mở session. Kiểm tra
 * validateExistingCurrentSessions chỉ áp dụng cho CurrentSessionContext; transaction JPA được
 * WorkspaceTransaction kiểm tra trực tiếp để không đổi tenant của EntityManager đang sống.
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
