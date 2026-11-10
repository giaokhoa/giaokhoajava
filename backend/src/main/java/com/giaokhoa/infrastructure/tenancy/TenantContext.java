package com.giaokhoa.infrastructure.tenancy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Context tenant của luồng xử lý hiện tại: workspace đang được phục vụ và schema của nó. Đây là
 * nguồn context duy nhất cho tầng truy cập dữ liệu — {@link WorkspaceTransaction} đặt context này
 * khi vào phạm vi một workspace, {@link WorkspaceTenantResolver} đọc nó để Hibernate định tuyến
 * session. Workspace chưa được cấp schema riêng (cột {@code workspace.schema_name} còn NULL) được
 * phân giải về schema hệ thống {@value #SYSTEM_SCHEMA} như trước đợt di trú schema-per-tenant.
 */
public final class TenantContext {
    public static final String SYSTEM_SCHEMA = "public";

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    /** Cặp workspace + schema đã phân giải; schema không bao giờ null trong context. */
    public record Context(UUID workspaceId, String schemaName) {}

    private TenantContext() {}

    /** Tên schema của một workspace: {@code t_} + UUID bỏ dấu gạch, tất định theo id. */
    public static String schemaNameFor(UUID workspaceId) {
        return "t_" + workspaceId.toString().replace("-", "");
    }

    public static void set(UUID workspaceId, @Nullable String schemaName) {
        HOLDER.set(new Context(workspaceId, schemaName != null ? schemaName : SYSTEM_SCHEMA));
    }

    public static @Nullable Context current() {
        return HOLDER.get();
    }

    /** Schema mà tầng truy cập dữ liệu phải dùng ở luồng hiện tại; mặc định là schema hệ thống. */
    public static String currentSchema() {
        Context context = HOLDER.get();
        return context != null ? context.schemaName() : SYSTEM_SCHEMA;
    }

    public static void restore(@Nullable Context previous) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
