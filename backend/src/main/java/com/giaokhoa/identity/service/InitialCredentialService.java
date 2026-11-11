package com.giaokhoa.identity.service;

import com.giaokhoa.identity.keycloak.IdentityProvisioningClient;
import com.giaokhoa.identity.service.IssuedStudentRegistrationService.IssuedStudent;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bàn giao credential ban đầu khi center cấp danh tính học sinh MỚI: tạo user Keycloak kèm
 * credential tạm, sau đó registrar mới ghi account/membership phía directory. Credential tạm chỉ
 * tồn tại trong kết quả của lần gọi này — không lưu xuống DB, không ghi log; caller chỉ được lộ nó
 * trong response tạo đầu tiên, mất kết quả thì đi qua khôi phục tập trung chứ không cấp lại qua
 * đường này. Chỉ tạo user mới: username đã tồn tại trên Keycloak trả 409, danh tính đã có không bị
 * nhận nuôi và không bị đặt lại credential. Lỗi nghiệp vụ hoặc lỗi DB sau khi identity đã tạo
 * (registrar từ chối, transaction hỏng) đều được reconcile bằng cách xoá user Keycloak vừa tạo:
 * user đó chưa có account hay eligibility nào nên xoá là an toàn và giải phóng username/sub; lỗi
 * khi xoá (nếu có) được gắn suppressed vào lỗi gốc thay vì nuốt mất dấu vết.
 */
@Service
public class InitialCredentialService {
    private static final SecureRandom random = new SecureRandom();
    private static final char[] lowercase = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] digits = "0123456789".toCharArray();

    private final IdentityProvisioningClient provisioning;
    private final IssuedStudentRegistrationService registrar;

    public InitialCredentialService(
            IdentityProvisioningClient provisioning, IssuedStudentRegistrationService registrar) {
        this.provisioning = provisioning;
        this.registrar = registrar;
    }

    /**
     * Kết quả bàn giao một lần: username, credential tạm và kết quả ghi directory của registrar.
     */
    public record Handoff(String username, String temporaryPassword, IssuedStudent issued) {}

    /**
     * Cấp danh tính học sinh mới trong một center. Origin CENTER_ISSUED do registrar xác lập phía
     * server; caller không truyền origin hay mật khẩu vào. Operator phải là OWNER/MANAGER ACTIVE
     * của center — tiền điều kiện do registrar kiểm tra khi ghi directory.
     */
    public Handoff issueNewStudent(
            UUID operatorUserId,
            UUID centerWorkspaceId,
            String username,
            String displayName,
            @Nullable String email) {
        Objects.requireNonNull(operatorUserId, "operatorUserId");
        Objects.requireNonNull(centerWorkspaceId, "centerWorkspaceId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        if (username.isBlank() || displayName.isBlank() || (email != null && email.isBlank())) {
            throw new IllegalArgumentException(
                    "Username and display name are required; email must not be blank when present");
        }

        String temporaryPassword = generateTemporaryPassword();
        String keycloakSub;
        try {
            keycloakSub = provisioning.createUser(username, displayName, email, temporaryPassword);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.USERNAME_TAKEN,
                        "Tên đăng nhập đã thuộc về một danh tính khác.",
                        ex);
            }
            throw unavailable(ex);
        } catch (RestClientException | OAuth2AuthorizationException ex) {
            throw unavailable(ex);
        }

        IssuedStudent issued;
        try {
            issued =
                    registrar.issueStudent(
                            operatorUserId, centerWorkspaceId, keycloakSub, username);
        } catch (HttpProblemException | DataAccessException | TransactionException failure) {
            // Identity đã tạo nhưng directory chưa ghi: xoá user vừa tạo để không bỏ lại
            // orphan chiếm username/sub mà không có eligibility.
            try {
                provisioning.deleteUser(keycloakSub);
            } catch (RestClientException | OAuth2AuthorizationException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new Handoff(username, temporaryPassword, issued);
    }

    private static String generateTemporaryPassword() {
        char[][] classes = {lowercase, uppercase, digits};
        char[] password = new char[24];
        for (int i = 0; i < password.length; i++) {
            char[] alphabet = classes[i % classes.length];
            password[i] = alphabet[random.nextInt(alphabet.length)];
        }
        for (int i = password.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = password[i];
            password[i] = password[j];
            password[j] = swap;
        }
        return new String(password);
    }

    private static HttpProblemException unavailable(Throwable cause) {
        return new HttpProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.IDENTITY_UNAVAILABLE,
                "Không xác minh được danh tính hiện hành, thử lại sau.",
                cause);
    }
}
