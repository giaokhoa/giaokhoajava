package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingScopePreconditions.WorkspaceInfo;
import com.giaokhoa.teaching.model.ClassStudent;
import com.giaokhoa.teaching.model.SchoolClass;
import com.giaokhoa.teaching.repository.ClassStudentRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.RosterListItem;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository.Page;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ lớp và roster ở tầng dữ kiện nguồn: tạo/đổi tên/thu hồi lớp, thêm/thu hồi học sinh
 * trong roster của đúng một lớp thuộc đúng một workspace. Mọi thao tác đi qua các repository đã
 * khóa phạm vi theo workspace nên dữ kiện của workspace khác không tồn tại đối với service này. Mỗi
 * phương thức đặt context workspace cho transaction qua {@link WorkspaceTransaction} ngay khi bắt
 * đầu: các bảng giảng dạy nằm dưới RLS theo đúng context đó, thiếu nó thì runtime role không
 * BYPASSRLS không thấy và không ghi được hàng nào. Mọi phương thức ghi giữ khóa workspace thống
 * nhất qua {@link ResourceMutationCoordinator} ngay sau khi đặt context — cùng mẫu các writer dữ
 * kiện nguồn khác — để dữ kiện nguồn mà projector chiếu không bao giờ thiếu một mutation đang chạy
 * đồng thời của cùng workspace.
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
    private static final List<String> CURRENT_STATES = List.of("PENDING", "ACTIVE");

    private final SchoolClassRepository schoolClasses;
    private final ClassStudentRepository classStudents;
    private final EnrollmentRepository enrollments;
    private final TeachingPageRepository pages;
    private final TeachingScopePreconditions preconditions;
    private final WorkspaceTransaction workspaceTransaction;
    private final ResourceMutationCoordinator coordinator;
    private final TeachingRevocationContributor revocationContributor;

    public ClassService(
            SchoolClassRepository schoolClasses,
            ClassStudentRepository classStudents,
            EnrollmentRepository enrollments,
            TeachingPageRepository pages,
            TeachingScopePreconditions preconditions,
            WorkspaceTransaction workspaceTransaction,
            ResourceMutationCoordinator coordinator,
            TeachingRevocationContributor revocationContributor) {
        this.schoolClasses = schoolClasses;
        this.classStudents = classStudents;
        this.enrollments = enrollments;
        this.pages = pages;
        this.preconditions = preconditions;
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
        preconditions.requireTeachingWorkspace(workspaceId);
        preconditions.requireSubscriptionCurrent(workspaceId);
        UUID classId = UUID.randomUUID();
        schoolClasses.save(new SchoolClass(classId, workspaceId, trimmed));
        return new ClassView(classId, workspaceId, trimmed, "ACTIVE", 0);
    }

    /** Metadata của một lớp trong đúng workspace; lớp ngoài workspace trả 404 như không tồn tại. */
    @Transactional(readOnly = true)
    public ClassView getClass(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        return toView(
                schoolClasses
                        .findByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound()));
    }

    @Transactional(readOnly = true)
    public Page<ClassView> pageClasses(
            UUID workspaceId,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable UUID homeroomTeacherId) {
        workspaceTransaction.applyContext(workspaceId);
        return pages.pageClasses(workspaceId, cursor, limit, homeroomTeacherId)
                .map(item ->
                        new ClassView(
                                item.getId(),
                                item.getWorkspaceId(),
                                item.getName(),
                                item.getState(),
                                item.getVersion()));
    }

    /** Các lớp hiện hành của workspace, cũ nhất trước; chỉ metadata, không kèm roster. */
    @Transactional(readOnly = true)
    public List<ClassView> listClasses(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        workspaceTransaction.applyContext(workspaceId);
        return schoolClasses
                .findByWorkspaceIdAndStateOrderByCreatedAtAscIdAsc(workspaceId, "ACTIVE")
                .stream()
                .map(ClassService::toView)
                .toList();
    }

    /**
     * Đổi tên lớp hiện hành; lớp đã thu hồi bị trigger giữ lịch sử khóa mọi thay đổi nên trả 409.
     * Là cập nhật đối tượng hiện có nên bắt buộc If-Match khớp row version hiện tại (thiếu trả 428,
     * lệch trả 409), kiểm tra sau khi khóa row và trước mọi ghi — cùng mẫu đổi role membership.
     */
    @Transactional
    public ClassView renameClass(
            UUID workspaceId, UUID classId, String name, @Nullable String ifMatch) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        String trimmed = normalizeName(name);
        SchoolClass row =
                schoolClasses
                        .lockByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.CLASS_ALREADY_REVOKED,
                    "Lớp đã bị thu hồi trước đó.");
        }
        IdempotencyService.requireIfMatch(ifMatch, row.getVersion());
        preconditions.requireSubscriptionCurrent(workspaceId);
        row.rename(trimmed);
        return new ClassView(
                row.getId(), row.getWorkspaceId(), trimmed, row.getState(), row.getVersion() + 1);
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
        SchoolClass row =
                schoolClasses
                        .lockByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.CLASS_ALREADY_REVOKED,
                    "Lớp đã bị thu hồi trước đó.");
        }
        row.revoke();
        revocationContributor.revokeDependentsOfClass(workspaceId, classId);
        return new ClassView(
                row.getId(), row.getWorkspaceId(), row.getName(), "REVOKED", row.getVersion() + 1);
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
                    ErrorCodes.ROSTER_STUDENTS_REQUIRED,
                    "Danh sách học sinh thêm vào roster không được rỗng.");
        }
        SchoolClass clazz =
                schoolClasses
                        .lockByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.getState());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.CLASS_NOT_ACTIVE,
                    "Lớp đã bị thu hồi, không thêm học sinh được nữa.");
        }
        preconditions.requireSubscriptionCurrent(workspaceId);
        WorkspaceInfo workspace = preconditions.requireTeachingWorkspace(workspaceId);
        for (UUID studentUserId : students) {
            requireStudentEligible(workspace, studentUserId);
        }
        List<ClassStudent> entries = new ArrayList<>();
        for (UUID studentUserId : students) {
            ClassStudent entry =
                    classStudents
                            .findByWorkspaceIdAndClassIdAndStudentUserIdAndStateIn(
                                    workspaceId, classId, studentUserId, CURRENT_STATES)
                            .orElseGet(() ->
                                    classStudents.save(
                                            new ClassStudent(
                                                    UUID.randomUUID(),
                                                    workspaceId,
                                                    classId,
                                                    studentUserId)));
            entries.add(entry);
        }
        // Tên hiển thị thuộc mặt phẳng hệ thống nên phân giải một lần cho cả lô từ danh sách ID.
        Map<UUID, String> usernames =
                preconditions.usernamesByIds(
                        entries.stream().map(ClassStudent::getStudentUserId).toList());
        return entries.stream().map(entry -> toView(entry, usernames)).toList();
    }

    @Transactional(readOnly = true)
    public Page<RosterEntryView> pageStudents(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        Page<RosterListItem> page = pages.pageRoster(workspaceId, classId, cursor, limit);
        Map<UUID, String> usernames =
                preconditions.usernamesByIds(
                        page.items().stream().map(RosterListItem::getStudentUserId).toList());
        return page.map(item ->
                new RosterEntryView(
                        item.getId(),
                        item.getStudentUserId(),
                        requireUsername(usernames, item.getStudentUserId()),
                        item.getState(),
                        item.getVersion()));
    }

    /** Roster hiện hành của lớp; đọc roster là thao tác riêng, không suy ra từ đọc lớp. */
    @Transactional(readOnly = true)
    public List<RosterEntryView> listStudents(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        List<ClassStudent> entries =
                classStudents.findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, CURRENT_STATES);
        Map<UUID, String> usernames =
                preconditions.usernamesByIds(
                        entries.stream().map(ClassStudent::getStudentUserId).toList());
        return entries.stream().map(entry -> toView(entry, usernames)).toList();
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
        ClassStudent row =
                classStudents
                        .lockByWorkspaceIdAndClassIdAndId(workspaceId, classId, classStudentId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        ErrorCodes.ROSTER_ENTRY_NOT_FOUND,
                                        "Không tìm thấy học sinh trong roster của lớp" + " này."));
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.ROSTER_ENTRY_ALREADY_REVOKED,
                    "Dòng roster đã bị thu hồi trước đó.");
        }
        row.revoke();
        enrollments.revokeCurrentByClassStudentId(workspaceId, classStudentId, CURRENT_STATES);
        return new RosterEntryView(
                row.getId(),
                row.getStudentUserId(),
                requireUsername(
                        preconditions.usernamesByIds(List.of(row.getStudentUserId())),
                        row.getStudentUserId()),
                "REVOKED",
                row.getVersion() + 1);
    }

    private void requireStudentEligible(WorkspaceInfo workspace, UUID studentUserId) {
        boolean center = "CENTER".equals(workspace.kind());
        if (center) {
            if (!preconditions.hasActiveCenterMembershipWithRole(
                    workspace.id(), studentUserId, "STUDENT")) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.STUDENT_NOT_CENTER_MEMBER,
                        "Học sinh phải là thành viên đang hoạt động của trung tâm mới vào được"
                                + " roster.");
            }
            return;
        }
        if (!preconditions.accountExists(studentUserId)) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.STUDENT_ACCOUNT_NOT_FOUND,
                    "Không tìm thấy tài khoản của học sinh.");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_CLASS_NAME,
                    "Tên lớp phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.CLASS_NOT_FOUND,
                "Không tìm thấy lớp trong workspace này.");
    }

    private static ClassView toView(SchoolClass row) {
        return new ClassView(
                row.getId(), row.getWorkspaceId(), row.getName(), row.getState(), row.getVersion());
    }

    private static RosterEntryView toView(ClassStudent entry, Map<UUID, String> usernames) {
        return new RosterEntryView(
                entry.getId(),
                entry.getStudentUserId(),
                requireUsername(usernames, entry.getStudentUserId()),
                entry.getState(),
                entry.getVersion());
    }

    private static String requireUsername(Map<UUID, String> usernames, UUID studentUserId) {
        // FK của dòng roster bảo đảm tài khoản của học sinh luôn tồn tại để phân giải tên.
        return Objects.requireNonNull(usernames.get(studentUserId), "username");
    }
}
