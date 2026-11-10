package com.giaokhoa.teaching;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.WorkspaceInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing dùng chung của các controller giảng dạy: resolve actor từ phiên, quyết quyền ở tầng
 * route và ghi operation cho các mutation mà service dữ kiện nguồn không tự ghi operation.
 *
 * <p>Quyết quyền ở tầng route tính từ đúng dữ kiện nguồn mà {@link TeachingPolicyProjector} chiếu
 * lên native graph — role của membership ACTIVE, phân công chủ nhiệm/giảng dạy hiện hành, ghi danh
 * hiện hành và chủ sở hữu của personal workspace — theo ngoại lệ SQL đã ghi ở mục 10 của hợp đồng
 * authorization (mở rộng cho route giảng dạy): quyết định có hiệu lực ngay từ dữ kiện nguồn, không
 * chờ projection áp xong, nên thu hồi phân công/membership chặn lời gọi tiếp theo trong cùng phiên.
 * Mỗi request đồng bộ eligibility hiện hành qua ACCOUNT/target fence trước khi quyết role bằng SQL;
 * khóa workspace bao trọn kiểm tra role, đọc/ghi nguồn và ghi operation tới commit.
 *
 * <p>Mọi đọc ghi chạm bảng giảng dạy (đang bật RLS theo context workspace) chạy trong {@link
 * WorkspaceTransaction#inWorkspace} để context được đặt trước truy vấn đầu tiên; thiếu context thì
 * role runtime không BYPASSRLS không thấy hàng nào, kể cả khi quyết quyền.
 */
@Component
public class TeachingRouteSupport {
    private static final JsonMapper JSON = new JsonMapper();

    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator revocations;
    private final TeachingRepository repository;
    private final WorkspaceTransaction workspaceTransaction;
    private final JdbcTemplate jdbc;
    private final ResourceMutationCoordinator coordinator;

    public TeachingRouteSupport(
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            SourceRevocationCoordinator revocations,
            TeachingRepository repository,
            WorkspaceTransaction workspaceTransaction,
            JdbcTemplate jdbc,
            ResourceMutationCoordinator coordinator) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.revocations = revocations;
        this.repository = repository;
        this.workspaceTransaction = workspaceTransaction;
        this.jdbc = jdbc;
        this.coordinator = coordinator;
    }

    /** Một trang danh sách đã lọc quyền: chỉ các dòng actor được phép thấy, không lộ tổng số. */
    public record Page<T>(
            List<T> items, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** Actor của phiên hiện hành; phiên không gắn được tài khoản directory thì fail-closed. */
    public UUID actor(Authentication authentication) {
        return executor.actorUserId(authentication);
    }

    /** Eligibility qua ACCOUNT fence; khóa workspace trước mọi kiểm tra quyền và dữ kiện nguồn. */
    public <T> T withEligibleActor(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            Function<UUID, T> work) {
        UUID actor = actor(authentication);
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.profileKey(actor),
                                List.of(
                                        PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                .scopeName()))),
                () ->
                        inWorkspace(
                                workspaceId,
                                () -> {
                                    coordinator.lockWorkspace(workspaceId);
                                    return work.apply(actor);
                                }));
    }

    /** Chạy một thao tác trong transaction có context workspace cho RLS. */
    private <T> T inWorkspace(UUID workspaceId, Supplier<T> work) {
        // Supplier của route luôn trả đáp án; inWorkspace chỉ trả null khi callback trả null.
        return Objects.requireNonNull(
                workspaceTransaction.inWorkspace(workspaceId, work), "route result");
    }

    /**
     * Actor có quyền quản trị giảng dạy của workspace: OWNER/MANAGER hiện hành của center, hoặc
     * chính chủ sở hữu của personal workspace — đúng hai nguồn quản trị mà projector chiếu.
     */
    public boolean isAdmin(UUID actorUserId, UUID workspaceId) {
        List<WorkspaceAccess> rows =
                jdbc.query(
                        "SELECT kind, owner_user_id FROM workspace WHERE id = ?",
                        (rs, _) ->
                                new WorkspaceAccess(
                                        rs.getString("kind"),
                                        rs.getObject("owner_user_id", UUID.class)),
                        workspaceId);
        if (rows.isEmpty()) {
            return false;
        }
        WorkspaceAccess workspace = rows.getFirst();
        boolean personal = "PERSONAL".equals(workspace.kind());
        if (personal) {
            return actorUserId.equals(workspace.ownerUserId());
        }
        boolean center = "CENTER".equals(workspace.kind());
        if (!center) {
            return false;
        }
        return repository.hasActiveCenterMembershipWithRole(workspaceId, actorUserId, "OWNER")
                || repository.hasActiveCenterMembershipWithRole(
                        workspaceId, actorUserId, "MANAGER");
    }

    /** Đòi quyền quản trị giảng dạy; không có thì 403 như mọi deny quyền hiện hành. */
    public void requireAdmin(UUID actorUserId, UUID workspaceId) {
        if (!isAdmin(actorUserId, workspaceId)) {
            throw denied();
        }
    }

    /** Quyền đọc một lớp: quản trị, hoặc chủ nhiệm hiện hành của chính lớp đó. */
    public boolean canReadClass(UUID actorUserId, UUID workspaceId, UUID classId) {
        return isAdmin(actorUserId, workspaceId)
                || repository
                        .currentHomeroomClassIdsOfUser(workspaceId, actorUserId)
                        .contains(classId);
    }

    /** Quyền đọc một khóa: quản trị, hoặc chủ nhiệm hiện hành của lớp chứa khóa. */
    public boolean canReadCourse(UUID actorUserId, UUID workspaceId, UUID classIdOfCourse) {
        return canReadClass(actorUserId, workspaceId, classIdOfCourse);
    }

    /**
     * Quyền đọc roster của một môn–khóa: quản trị, hoặc giáo viên đang được phân công đúng môn–khóa
     * đó.
     */
    public boolean canReadCourseSubjectRoster(
            UUID actorUserId, UUID workspaceId, UUID courseSubjectId) {
        return isAdmin(actorUserId, workspaceId)
                || repository
                        .currentTeachingCourseSubjectIdsOfUser(workspaceId, actorUserId)
                        .contains(courseSubjectId);
    }

    /** Hash payload cho idempotency, cùng cách các controller thành viên đang dùng. */
    public String payloadHash(String method, String targetPath, @Nullable Object body) {
        return idempotency.payloadHash(
                method,
                targetPath,
                JSON.valueToTree(body == null ? JSON.createObjectNode() : body));
    }

    /** Operation đã ghi cho cùng actor/key/payload, nếu lời gọi này là replay. */
    public Optional<AuthorizationOperation> replay(
            UUID actorUserId, String key, String payloadHash) {
        idempotency.requireKey(key);
        return idempotency.findExisting(actorUserId, key, payloadHash);
    }

    /**
     * Ghi operation ghi lại bundle hiện hành của các target bị một mutation dữ kiện nguồn chạm tới
     * kèm toàn bộ bundle kết quả của workspace (thu hồi lớp/khóa/môn–khóa/roster đóng ghi danh và
     * phân công phụ thuộc, vốn là nguồn của bundle kết quả), trong cùng transaction của mutation —
     * đường ghi operation cho các service lớp/khóa vốn không tự ghi operation.
     */
    public AuthorizationOperation recordChange(
            UUID actorUserId,
            UUID workspaceId,
            String key,
            String payloadHash,
            List<String> targetKeys) {
        return revocations.recordTeachingRevocation(
                actorUserId, workspaceId, key, payloadHash, targetKeys);
    }

    /** Biên nhận operation theo hợp đồng OperationView: 202 khi chưa xong, 200 khi đã SUCCEEDED. */
    public ResponseEntity<IdempotencyService.OperationReceipt> receipt(
            AuthorizationOperation operation) {
        return idempotency.responseFor(operation);
    }

    /** Dùng trang đã giới hạn và lọc tại DB, giữ nguyên HTTP DTO và cache policy. */
    public <T> ResponseEntity<Page<T>> page(TeachingRepository.Page<T> page) {
        return ok(new Page<>(page.items(), page.nextCursor()));
    }

    /** Đáp án đọc được bảo vệ: không cache theo đúng hợp đồng HTTP. */
    public <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    /** Cổng gói dùng chung của các service giảng dạy: kỳ gói phải còn hiệu lực hiện hành. */
    public static void requireSubscriptionCurrent(TeachingRepository repository, UUID workspaceId) {
        if (!repository.subscriptionCurrent(workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    /** Workspace giảng dạy hợp lệ: đang ACTIVE và là center hoặc personal. */
    public static WorkspaceInfo requireTeachingWorkspace(
            TeachingRepository repository, UUID workspaceId) {
        WorkspaceInfo workspace =
                repository
                        .findWorkspace(workspaceId)
                        .orElseThrow(() -> teachingWorkspaceNotFound());
        if (!"ACTIVE".equals(workspace.state())
                || (!"CENTER".equals(workspace.kind()) && !"PERSONAL".equals(workspace.kind()))) {
            throw teachingWorkspaceNotFound();
        }
        return workspace;
    }

    private static HttpProblemException teachingWorkspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy workspace giảng dạy hợp lệ.");
    }

    public static HttpProblemException denied() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Không có quyền thực hiện thao tác.");
    }

    /** Khóa target của một lớp theo template cố định của projector. */
    public static String classTarget(UUID workspaceId, UUID classId) {
        return NativePolicyTemplates.classKey(workspaceId, classId);
    }

    /** Khóa target của một khóa theo template cố định của projector. */
    public static String courseTarget(UUID workspaceId, UUID courseId) {
        return NativePolicyTemplates.courseKey(workspaceId, courseId);
    }

    /** Khóa target của một môn–khóa theo template cố định của projector. */
    public static String courseSubjectTarget(UUID workspaceId, UUID courseSubjectId) {
        return NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
    }

    private record WorkspaceAccess(String kind, @Nullable UUID ownerUserId) {}
}
