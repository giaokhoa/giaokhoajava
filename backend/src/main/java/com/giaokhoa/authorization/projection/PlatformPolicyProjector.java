package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.util.ArrayList;
import java.util.List;

/**
 * Projector của platform: nguồn sự thật duy nhất về desired native graph của target {@code
 * platform}. Graph là tĩnh theo hợp đồng — nguồn duy nhất là group policy của group operator nền
 * tảng hiện hành, không suy ra từ dữ kiện nghiệp vụ nào; membership do PDP tự đối chiếu theo group
 * realm hiện hành ở mỗi quyết định. Desired state của cả target ghi thành một projection bundle duy
 * nhất, tính hash với scope danh nghĩa đầu tiên của catalog — cùng hình dạng bundle của CENTER
 * workspace, vì một target chỉ có đúng một projection.
 */
public final class PlatformPolicyProjector {

    private static final String OPERATOR_DESCRIPTOR = "PLATFORM_OPERATOR";

    private PlatformPolicyProjector() {}

    /** Các scope platform mà projector này quản lý, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.PlatformScope.values());
    }

    /** Scope danh nghĩa mà desired hash của bundle platform được tính theo. */
    public static String bundleScope() {
        return PermissionCatalog.PlatformScope.CENTER_CREATE.scopeName();
    }

    /** Canonical descriptor của nguồn operator cho một scope của target platform. */
    public static String operatorDescriptor(String scope) {
        return OPERATOR_DESCRIPTOR
                + "|group="
                + NativePolicyTemplates.PLATFORM_OPERATORS_GROUP
                + "|target="
                + NativePolicyTemplates.platformKey()
                + "|scope="
                + scope;
    }

    /** Các canonical source descriptor của một scope: đúng một nguồn operator. */
    public static List<String> sourcesFor(String scope) {
        return List.of(operatorDescriptor(scope));
    }

    /** Toàn bộ descriptor của bundle platform: một nguồn operator cho mỗi scope của catalog. */
    public static List<String> bundleDescriptors() {
        List<String> bundle = new ArrayList<>();
        for (String scope : managedScopes()) {
            bundle.add(operatorDescriptor(scope));
        }
        return List.copyOf(bundle);
    }

    /**
     * Các gate native của một canonical descriptor platform: đúng một gate group operator. Dùng bởi
     * reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2 || !OPERATOR_DESCRIPTOR.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported platform descriptor: " + descriptor);
        }
        for (String part : parts) {
            if (part.startsWith("group=") && part.length() > "group=".length()) {
                return List.of(
                        new PolicyGraph.Gate.GroupMembership(part.substring("group=".length())));
            }
        }
        throw new IllegalArgumentException("Platform descriptor has no group: " + descriptor);
    }
}
