package com.giaokhoa.teaching;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.CourseSubjectScopeRow;
import com.giaokhoa.teaching.data.TeachingRepository.EnrollmentRow;
import com.giaokhoa.teaching.data.TeachingRepository.RosterRow;
import com.giaokhoa.teaching.data.TeachingRepository.WorkspaceInfo;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ ghi danh ở tầng dữ kiện nguồn và nguồn quyền: ghi danh theo lô (mọi dòng roster đã chọn
 * nhân mọi môn–khóa đã chọn) và thu hồi một ghi danh. Ghi danh là thay đổi nguồn quyền — học sinh
 * thành nguồn của group học sinh thuộc môn–khóa — nên mỗi mutation ghi một authorization operation
 * qua {@link SourceRevocationCoordinator} với bundle hiện hành của từng môn–khóa bị ảnh hưởng kèm
 * toàn bộ bundle kết quả của workspace (ghi danh là nguồn của {@code read.published}), và ghi danh
 * chỉ được coi là đã đồng bộ khi operation SUCCEEDED; service không tự quyết quyền thay Keycloak
 * PDP (quyết định cho phép ở route) và không báo hoàn tất khi operation còn PENDING.
 *
 * <p>Toàn bộ lô được kiểm tra trước khi ghi bất cứ dòng nào (theo mẫu lô roster): danh sách hai
 * phía không được rỗng, mọi dòng roster phải hiện hành và thuộc đúng workspace, mọi môn–khóa phải
 * hiện hành, và mọi cặp phải cùng một lớp — một phần tử không hợp lệ làm cả lô bị từ chối 400 và
 * không ghi phần nào (trigger cùng-lớp ở DB chỉ là chốt chặn cuối). Ghi danh theo ngữ nghĩa hợp
 * tập: cặp đã có dòng hiện hành thì giữ nguyên dòng đó, chưa có thì tạo dòng ACTIVE mới (kể cả khi
 * từng có dòng đã thu hồi — lịch sử cũ không bị phục hồi). Ghi danh là thao tác mở rộng nên bị chặn
 * khi gói đã hết hiệu lực; thu hồi thì không bị chặn bởi gói. Với workspace CENTER, dòng roster
 * hiện hành đã kéo theo membership ACTIVE tại lúc vào lớp và việc gỡ membership đóng cả roster lẫn
 * ghi danh qua contributor, nên service không kiểm tra lại membership ở đây; ghi danh cho PERSONAL
 * đi cùng đường này (owner tổ chức qua route), nguồn personal thuộc projector riêng.
 *
 * <p>Như các service giảng dạy khác, mỗi phương thức đặt context workspace cho transaction qua
 * {@link WorkspaceTransaction} ngay khi bắt đầu vì các bảng ghi danh nằm dưới RLS theo đúng context
 * đó. Các mutation trong cùng một workspace được tuần tự hoá bằng khóa workspace thống nhất (xem
 * {@link #lockWorkspace}) giữ tới commit, để bundle desired mà projector tính từ dữ kiện nguồn
 * không bao giờ là ảnh chụp thiếu thay đổi của một mutation đang chạy đồng thời trong cùng
 * workspace.
 */
@Service
public class EnrollmentService {
    private final TeachingRepository repository;
    private final WorkspaceTransaction workspaceTransaction;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator revocations;
    private final ResourceMutationCoordinator coordinator;

    public EnrollmentService(
            TeachingRepository repository,
            WorkspaceTransaction workspaceTransaction,
            IdempotencyService idempotency,
            SourceRevocationCoordinator revocations,
            ResourceMutationCoordinator coordinator) {
        this.repository = repository;
        this.workspaceTransaction = workspaceTransaction;
        this.idempotency = idempotency;
        this.revocations = revocations;
        this.coordinator = coordinator;
    }

    /** Một ghi danh: định danh dòng, hai phía của cặp và trạng thái hiện hành tối thiểu. */
    public record EnrollmentView(
            UUID id,
            UUID classStudentId,
            UUID courseSubjectId,
            UUID studentUserId,
            String state,
            long version) {}

    /**
     * Ghi danh theo lô: tích Descartes của các dòng roster đã chọn với các môn–khóa đã chọn, mọi
     * cặp phải cùng một lớp. Trả operation chưa hoàn tất; replay cùng idempotency key và payload
     * trả lại đúng operation đã ghi.
     */
    @Transactional
    public AuthorizationOperation enrollBatch(
            UUID initiatorUserId,
            UUID workspaceId,
            List<UUID> classStudentIds,
            List<UUID> courseSubjectIds,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classStudentIds, "classStudentIds");
        Objects.requireNonNull(courseSubjectIds, "courseSubjectIds");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<UUID> rosterIds = List.copyOf(new LinkedHashSet<>(classStudentIds));
        List<UUID> subjectIds = List.copyOf(new LinkedHashSet<>(courseSubjectIds));
        if (rosterIds.isEmpty() || subjectIds.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ENROLLMENT_BATCH_REQUIRED",
                    "Lô ghi danh cần ít nhất một học sinh và một môn–khóa.");
        }
        WorkspaceInfo workspace = requireTeachingWorkspace(workspaceId);
        List<RosterRow> roster = new ArrayList<>();
        for (UUID rosterId : rosterIds) {
            RosterRow entry =
                    repository
                            .findRosterEntryById(workspaceId, rosterId)
                            .orElseThrow(() -> enrollmentTargetNotFound());
            if (!isCurrent(entry.state())) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        "ROSTER_ENTRY_NOT_CURRENT",
                        "Học sinh không còn trong roster hiện hành của lớp.");
            }
            if ("CENTER".equals(workspace.kind())
                    && !repository.hasActiveCenterMembershipWithRole(
                            workspaceId, entry.studentUserId(), "STUDENT")) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        "STUDENT_NOT_CENTER_STUDENT",
                        "Người được ghi danh phải là học sinh đang hoạt động của trung tâm.");
            }
            roster.add(entry);
        }
        List<CourseSubjectScopeRow> subjects = new ArrayList<>();
        for (UUID courseSubjectId : subjectIds) {
            CourseSubjectScopeRow subject =
                    repository
                            .findCourseSubjectScope(workspaceId, courseSubjectId)
                            .orElseThrow(() -> enrollmentTargetNotFound());
            if (!isCurrent(subject.state())) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        "COURSE_SUBJECT_NOT_CURRENT",
                        "Môn–khóa không còn hiện hành trong khóa.");
            }
            var clazz =
                    repository
                            .findClass(workspaceId, subject.classId())
                            .orElseThrow(EnrollmentService::enrollmentTargetNotFound);
            var course =
                    repository
                            .findCourse(workspaceId, subject.courseId())
                            .orElseThrow(EnrollmentService::enrollmentTargetNotFound);
            if (!"ACTIVE".equals(clazz.state()) || !"ACTIVE".equals(course.state())) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        "TEACHING_CONTEXT_NOT_ACTIVE",
                        "Lớp hoặc khóa đã bị thu hồi, không ghi danh mới được nữa.");
            }
            subjects.add(subject);
        }
        UUID classId = roster.getFirst().classId();
        boolean sameClass =
                roster.stream().allMatch(entry -> classId.equals(entry.classId()))
                        && subjects.stream().allMatch(subject -> classId.equals(subject.classId()));
        if (!sameClass) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ENROLLMENT_CLASS_MISMATCH",
                    "Mọi học sinh và môn–khóa trong một lô ghi danh phải cùng một lớp.");
        }
        requireSubscriptionCurrent(workspaceId);
        for (RosterRow entry : roster) {
            for (CourseSubjectScopeRow subject : subjects) {
                if (repository
                        .findCurrentEnrollment(workspaceId, entry.id(), subject.id())
                        .isEmpty()) {
                    repository.insertEnrollment(
                            UUID.randomUUID(), workspaceId, entry.id(), subject.id());
                }
            }
        }
        List<String> targets =
                subjects.stream()
                        .map(subject ->
                                NativePolicyTemplates.courseSubjectKey(workspaceId, subject.id()))
                        .toList();
        return revocations.recordTeachingRevocation(
                initiatorUserId, workspaceId, idempotencyKey, payloadHash, targets);
    }

    /**
     * Thu hồi một ghi danh: đổi state/revoked_at, giữ lịch sử, và ghi lại bundle của môn–khóa của
     * nó kèm toàn bộ bundle kết quả của workspace trong cùng operation (ghi danh là nguồn của
     * {@code read.published}). Thu hồi không bị chặn bởi gói hết hạn.
     */
    @Transactional
    public AuthorizationOperation revokeEnrollment(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID enrollmentId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(enrollmentId, "enrollmentId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        EnrollmentRow row =
                repository
                        .lockEnrollment(workspaceId, enrollmentId)
                        .orElseThrow(() -> enrollmentNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "ENROLLMENT_ALREADY_REVOKED",
                    "Ghi danh đã bị thu hồi trước đó.");
        }
        repository.revokeEnrollment(enrollmentId);
        return revocations.recordTeachingRevocation(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        NativePolicyTemplates.courseSubjectKey(
                                workspaceId, row.courseSubjectId())));
    }

    @Transactional(readOnly = true)
    public TeachingRepository.Page<EnrollmentView> pageEnrollments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        repository
                .findCourseSubjectScope(workspaceId, courseSubjectId)
                .orElseThrow(() -> enrollmentTargetNotFound());
        return repository
                .pageEnrollments(workspaceId, courseSubjectId, cursor, limit)
                .map(row ->
                        new EnrollmentView(
                                row.id(),
                                row.classStudentId(),
                                row.courseSubjectId(),
                                row.studentUserId(),
                                row.state(),
                                row.version()));
    }

    /** Các ghi danh hiện hành của một môn–khóa, theo thứ tự tạo ổn định. Chỉ đọc dữ kiện nguồn. */
    @Transactional(readOnly = true)
    public List<EnrollmentView> listEnrollments(UUID workspaceId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        workspaceTransaction.applyContext(workspaceId);
        repository
                .findCourseSubjectScope(workspaceId, courseSubjectId)
                .orElseThrow(() -> enrollmentTargetNotFound());
        return repository.listCurrentEnrollments(workspaceId, courseSubjectId).stream()
                .map(row ->
                        new EnrollmentView(
                                row.id(),
                                row.classStudentId(),
                                row.courseSubjectId(),
                                row.studentUserId(),
                                row.state(),
                                row.version()))
                .toList();
    }

    private static boolean isCurrent(String state) {
        return "PENDING".equals(state) || "ACTIVE".equals(state);
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

    private static HttpProblemException enrollmentTargetNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "ENROLLMENT_TARGET_NOT_FOUND",
                "Không tìm thấy học sinh hoặc môn–khóa trong workspace này.");
    }

    private static HttpProblemException enrollmentNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "ENROLLMENT_NOT_FOUND",
                "Không tìm thấy ghi danh trong workspace này.");
    }

    private static HttpProblemException workspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy workspace giảng dạy hợp lệ.");
    }

    /**
     * Tuần tự hoá mọi mutation của một workspace trên khóa workspace thống nhất của {@link
     * ResourceMutationCoordinator} — cùng một khóa với CenterService, SubscriptionService và các
     * service lớp/khóa — để dữ kiện nguồn mà projector chiếu không bao giờ thiếu một mutation đang
     * chạy đồng thời của cùng workspace.
     */
    private void lockWorkspace(UUID workspaceId) {
        coordinator.lockWorkspace(workspaceId);
    }
}
