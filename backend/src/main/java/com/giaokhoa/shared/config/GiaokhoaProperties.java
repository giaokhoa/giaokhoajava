package com.giaokhoa.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Toàn bộ cây cấu hình {@code giaokhoa.*} bind vào một cấu trúc có kiểu và được validate ngay khi
 * khởi động: thiếu hoặc rỗng bất kỳ giá trị bắt buộc nào là lỗi khởi động thay vì lỗi lúc chạy ở
 * client đầu tiên chạm tới. Đây là nguồn sự thật duy nhất thay cho các điểm {@code @Value} rải rác
 * trước đây; metadata cấu hình được sinh tự động từ class này.
 *
 * @param appOrigin Origin công khai của ứng dụng, dùng cho cookie và chuyển hướng sau đăng xuất.
 * @param keycloak Cấu hình truy cập Keycloak của backend.
 */
@Validated
@ConfigurationProperties(prefix = "giaokhoa")
public record GiaokhoaProperties(@NotBlank String appOrigin, @Valid @NotNull Keycloak keycloak) {

    /**
     * @param adminBaseUrl Base URL của Keycloak Admin API mà backend truy cập nội bộ.
     * @param authorizationAudience Client ID của resource server dùng cho quyết định quyền UMA.
     * @param provisioner Credential của service account đồng bộ directory và policy graph.
     */
    public record Keycloak(
            @NotBlank String adminBaseUrl,
            @NotBlank String authorizationAudience,
            @Valid @NotNull Provisioner provisioner) {

        /**
         * @param clientId Client ID của service account dùng để đồng bộ directory và policy graph.
         * @param clientSecret Client secret của provisioner, được cấp từ biến môi trường phía
         *     server.
         */
        public record Provisioner(@NotBlank String clientId, @NotBlank String clientSecret) {}
    }
}
