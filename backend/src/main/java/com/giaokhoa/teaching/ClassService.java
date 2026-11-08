package com.giaokhoa.teaching;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.ClassRow;
import com.giaokhoa.teaching.data.TeachingRepository.RosterRow;
import com.giaokhoa.teaching.data.TeachingRepository.WorkspaceInfo;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ lớp và roster ở tầng dữ kiện nguồn: tạo/đổi tên/thu hồi lớp, thêm/thu hồi học sinh
 * trong roster của đúng một lớp thuộc đúng một workspace. Mọi thao tác đi qua {@link
 * TeachingRepository} đã khóa phạm vi theo workspace nên dữ kiện của workspace khác không tồn tại
 * đối với service này. Mỗi phương thức đặt context workspace cho transaction qua {@link
 * WorkspaceTransaction} ngay khi bắt đầu: các bảng giảng dạy nằm dưới RLS theo đúng context đó,
 * thiếu nó thì runtime role không BYPASSRLS không thấy và không ghi được hàng nào. Mọi phương thức
 * ghi giữ khóa workspace thống nhất qua {@link ResourceMutationCoordinator} ngay sau khi đặt
 * context — cùng mẫu các writer dữ kiện nguồn khác — để dữ kiện nguồn mà projector chiếu không bao
 * giờ thiếu một mutation đang chạy đồng thời của cùng workspace.
 *
 * <p>Service chỉ giữ bất biến dữ kiện và trạng thái, không tự quyết quyền thay Keycloak PDP và
 * không ghi authorization operation: quyết định cho phép ở route (PDP/fence hay biên SQL) và việc
 * ghi operation/projection thuộc lớp route và lớp projection riêng. Các kiểm tra trong service này
 * là tiền điều kiện dữ kiện mà tầng trên không thay được: workspace phải là CENTER hoặc PERSONAL
 * đang ACTIVE; mở rộng (tạo lớp, thêm roster, đổi tên lớp theo hợp đồng) bị chặn khi gói đã hết
 * hiệu lực, còn thu hồi thì không bị chặn bởi gói; học sinh vào roster của center phải có
 * membership ACTIVE của chính center đó (học sinh personal chỉ cần tài khoản tồn tại) — kiểm tra
 * trước ở đây để trả 400 nghiệp vụ, trigger ở DB chỉ là chốt chặn cuối. Thu hồi roster đồng thời
 * thu hồi ghi danh phụ thuộc trong cùng transaction và giữ nguyên toàn bộ lịch sử.
 */
@Service
public class ClassService {
    private final TeachingRepository repository;
    private final WorkspaceTransaction workspaceTransaction;
    private final ResourceMutationCoordinator coordinator;
    private final TeachingRevocationContributor revocationContributor;

    public ClassService(
            TeachingRepository repository,
            WorkspaceTransaction workspaceTransaction,
            ResourceMutationCoordinator coordinator,
            TeachingRevocationContributor revocationContributor) {
        this.repository = repository;
        this.workspaceTransaction = workspaceTransaction;
        this.coordinator = coordinator;
        this.revocationContributor = revocationContributor;
    }

    /** Metadata của một lớp; đọc lớp chỉ trả metadata này, không kèm roster. */
    public record ClassView(UUID id, UUID workspaceId, String name, String state, long version) {}

    /** Một dòng roster: định danh dòng, học sinh và trạng thái hiện hành tối thiểu. */
    public record RosterEntryView(
            UUID id, UUID studentUserId, String username, String state, long version) {}

    /** Tạo lớp ACTIVE trong một workspace CENTER hoặc PERSONAL đang ACTIVE. */
    @Transactional
    public ClassView createClass(UUID workspaceId, String name) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        String trimmed = normalizeName(name);
        requireTeachingWorkspace(workspaceId);
        requireSubscriptionCurrent(workspaceId);
        UUID classId = UUID.randomUUID();
        repository.insertClass(classId, workspaceId, trimmed);
        return new ClassView(classId, workspaceId, trimmed, "ACTIVE", 0);
    }

    /** Metadata của một lớp trong đúng workspace; lớp ngoài workspace trả 404 như không tồn tại. */
    @Transactional(readOnly = true)
    public ClassView getClass(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        return toView(
                repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound()));
    }

    /** Các lớp hiện hành của workspace, cũ nhất trước; chỉ metadata, không kèm roster. */
    @Transactional(readOnly = true)
    public List<ClassView> listClasses(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        workspaceTransaction.applyContext(workspaceId);
        return repository.listClasses(workspaceId).stream().map(ClassService::toView).toList();
    }

    /**
     * Đổi tên lớp hiện hành; lớp đã thu hồi bị trigger giữ lịch sử khóa mọi thay đổi nên trả 409.
     */
    @Transactional
    public ClassView renameClass(UUID workspaceId, UUID classId, String name) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        String trimmed = normalizeName(name);
        ClassRow row =
                repository.lockClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "CLASS_ALREADY_REVOKED", "Lớp đã bị thu hồi trước đó.");
        }
        requireSubscriptionCurrent(workspaceId);
        repository.renameClass(classId, trimmed);
        return new ClassView(row.id(), row.workspaceId(), trimmed, row.state(), row.version() + 1);
    }

    /**
     * Thu hồi lớp: đổi state của lớp và đóng luôn ghi danh, phân công giảng dạy và phân công chủ
     * nhiệm phụ thuộc lớp trong cùng transaction qua {@link TeachingRevocationContributor}, giữ
     * nguyên toàn bộ lịch sử. Thu hồi không bị chặn bởi gói hết hạn.
     */
    @Transactional
    public ClassView revokeClass(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        ClassRow row =
                repository.lockClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "CLASS_ALREADY_REVOKED", "Lớp đã bị thu hồi trước đó.");
        }
        repository.revokeClass(classId);
        revocationContributor.revokeDependentsOfClass(workspaceId, classId);
        return new ClassView(row.id(), row.workspaceId(), row.name(), "REVOKED", row.version() + 1);
    }

    /**
     * Thêm học sinh vào roster của lớp theo ngữ nghĩa hợp tập: học sinh đã có dòng hiện hành thì
     * giữ nguyên dòng đó, chưa có thì tạo dòng ACTIVE mới (kể cả khi từng có dòng đã thu hồi — lịch
     * sử cũ không bị phục hồi). Toàn bộ lô được kiểm tra trước khi ghi bất cứ dòng nào: một học
     * sinh không đủ tiền điều kiện làm cả lô bị từ chối 400 và không ghi phần nào.
     */
    @Transactional
    public List<RosterEntryView> addStudents(
            UUID workspaceId, UUID classId, List<UUID> studentUserIds) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(studentUserIds, "studentUserIds");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        List<UUID> students = List.copyOf(new LinkedHashSet<>(studentUserIds));
        if (students.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ROSTER_STUDENTS_REQUIRED",
                    "Danh sách học sinh thêm vào roster không được rỗng.");
        }
        ClassRow clazz =
                repository.lockClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.state());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "CLASS_NOT_ACTIVE",
                    "Lớp đã bị thu hồi, không thêm học sinh được nữa.");
        }
        requireSubscriptionCurrent(workspaceId);
        WorkspaceInfo workspace = requireTeachingWorkspace(workspaceId);
        for (UUID studentUserId : students) {
            requireStudentEligible(workspace, studentUserId);
        }
        List<RosterEntryView> result = new ArrayList<>();
        for (UUID studentUserId : students) {
            RosterRow entry =
                    repository
                            .findCurrentRosterEntry(workspaceId, classId, studentUserId)
                            .orElseGet(() -> {
                                repository.insertRosterEntry(
                                        UUID.randomUUID(), workspaceId, classId, studentUserId);
                                return repository
                                        .findCurrentRosterEntry(workspaceId, classId, studentUserId)
                                        .orElseThrow(() ->
                                                new IllegalStateException(
                                                        "Roster entry missing"
                                                                + " after"
                                                                + " insert"));
                            });
            result.add(toView(entry));
        }
        return List.copyOf(result);
    }

    /** Roster hiện hành của lớp; đọc roster là thao tác riêng, không suy ra từ đọc lớp. */
    @Transactional(readOnly = true)
    public List<RosterEntryView> listStudents(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        return repository.listRoster(workspaceId, classId).stream()
                .map(ClassService::toView)
                .toList();
    }

    /**
     * Thu hồi một dòng roster: đổi state/revoked_at, giữ lịch sử, và thu hồi luôn ghi danh hiện
     * hành phụ thuộc dòng roster đó trong cùng transaction. Thu hồi không bị chặn bởi gói.
     */
    @Transactional
    public RosterEntryView revokeStudent(UUID workspaceId, UUID classId, UUID classStudentId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(classStudentId, "classStudentId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        RosterRow row =
                repository
                        .lockRosterEntry(workspaceId, classId, classStudentId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "ROSTER_ENTRY_NOT_FOUND",
                                        "Không tìm thấy học sinh trong roster của lớp" + " này."));
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "ROSTER_ENTRY_ALREADY_REVOKED",
                    "Dòng roster đã bị thu hồi trước đó.");
        }
        repository.revokeRosterEntry(classStudentId);
        repository.revokeEnrollmentsOfRosterEntry(workspaceId, classStudentId);
        return new RosterEntryView(
                row.id(), row.studentUserId(), row.username(), "REVOKED", row.version() + 1);
    }

    private void requireStudentEligible(WorkspaceInfo workspace, UUID studentUserId) {
        boolean center = "CENTER".equals(workspace.kind());
        if (center) {
            if (!repository.hasActiveCenterMembership(workspace.id(), studentUserId)) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        "STUDENT_NOT_CENTER_MEMBER",
                        "Học sinh phải là thành viên đang hoạt động của trung tâm mới vào được"
                                + " roster.");
            }
            return;
        }
        if (!repository.accountExists(studentUserId)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "STUDENT_ACCOUNT_NOT_FOUND",
                    "Không tìm thấy tài khoản của học sinh.");
        }
    }

    private WorkspaceInfo requireTeachingWorkspace(UUID workspaceId) {
        WorkspaceInfo workspace =
                repository.findWorkspace(workspaceId).orElseThrow(() -> workspaceNotFound());
        if (!"ACTIVE".equals(workspace.state())
                || (!"CENTER".equals(workspace.kind()) && !"PERSONAL".equals(workspace.kind()))) {
            throw workspaceNotFound();
        }
        return workspace;
    }

    private void requireSubscriptionCurrent(UUID workspaceId) {
        if (!repository.subscriptionCurrent(workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CLASS_NAME",
                    "Tên lớp phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND", "Không tìm thấy lớp trong workspace này.");
    }

    private static HttpProblemException workspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy workspace giảng dạy hợp lệ.");
    }

    private static ClassView toView(ClassRow row) {
        return new ClassView(row.id(), row.workspaceId(), row.name(), row.state(), row.version());
    }

    private static RosterEntryView toView(RosterRow row) {
        return new RosterEntryView(
                row.id(), row.studentUserId(), row.username(), row.state(), row.version());
    }
}
