package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.CourseRow;
import com.giaokhoa.teaching.data.TeachingRepository.CourseSubjectRow;
import com.giaokhoa.teaching.data.TeachingRepository.SubjectRow;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
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
 * Nghiệp vụ khóa, môn của khóa và danh mục môn ở tầng dữ kiện nguồn: tạo/đổi tên/thu hồi khóa,
 * gắn/gỡ môn của khóa, đọc danh mục môn tham chiếu. Mọi thao tác đi qua {@link TeachingRepository}
 * đã khóa phạm vi theo workspace và lớp cha. Như {@link ClassService}, mỗi phương thức có phạm vi
 * workspace đặt context workspace cho transaction qua {@link WorkspaceTransaction} ngay khi bắt đầu
 * để RLS ở tầng DB khóa đúng cùng phạm vi; riêng danh mục môn là dữ liệu dùng chung, không có RLS
 * nên không cần context. Mọi phương thức ghi giữ khóa workspace thống nhất qua {@link
 * ResourceMutationCoordinator} ngay sau khi đặt context, như {@link ClassService}.
 *
 * <p>Như {@link ClassService}, service này chỉ giữ bất biến dữ kiện và trạng thái — không tự quyết
 * quyền thay Keycloak PDP, không ghi authorization operation. Bất biến riêng của khóa: danh sách
 * môn lúc tạo không được rỗng (kiểm tra ở đây trước khi ghi bất cứ thứ gì, DB cố ý không có trigger
 * đếm vì khóa được phép trở nên rỗng về sau khi gỡ môn cuối cùng); môn gắn vào khóa phải có trong
 * danh mục đang ACTIVE; gắn thêm môn chỉ tạo dòng môn–khóa mới, tuyệt đối không tự tạo ghi danh hay
 * phân công cho môn đó. Mở rộng (tạo khóa, gắn môn) bị chặn khi gói đã hết hiệu lực, thu hồi thì
 * không.
 */
@Service
public class CourseService {
    private final TeachingRepository repository;
    private final WorkspaceTransaction workspaceTransaction;
    private final ResourceMutationCoordinator coordinator;
    private final TeachingRevocationContributor revocationContributor;

    public CourseService(
            TeachingRepository repository,
            WorkspaceTransaction workspaceTransaction,
            ResourceMutationCoordinator coordinator,
            TeachingRevocationContributor revocationContributor) {
        this.repository = repository;
        this.workspaceTransaction = workspaceTransaction;
        this.coordinator = coordinator;
        this.revocationContributor = revocationContributor;
    }

    /** Một môn trong khóa theo đúng hình dạng hợp đồng CourseView. */
    public record SubjectEntry(UUID id, UUID subjectId, String name) {}

    /** Khóa kèm các môn hiện hành của nó. */
    public record CourseView(
            UUID id, UUID classId, String name, List<SubjectEntry> subjects, long version) {
        public CourseView {
            subjects = List.copyOf(subjects);
        }
    }

    /** Một môn của danh mục tham chiếu dùng chung. */
    public record SubjectView(UUID id, String code, String name) {}

    /**
     * Tạo khóa ACTIVE kèm các môn đã chọn trong đúng một lớp của workspace. Danh sách môn rỗng bị
     * từ chối 400 trước khi ghi; từng môn phải có trong danh mục đang ACTIVE.
     */
    @Transactional
    public CourseView createCourse(
            UUID workspaceId, UUID classId, String name, List<UUID> subjectIds) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        String trimmed = normalizeName(name);
        List<UUID> subjects = requireSubjects(subjectIds);
        var clazz = repository.lockClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.state());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "CLASS_NOT_ACTIVE",
                    "Lớp đã bị thu hồi, không tạo khóa được nữa.");
        }
        TeachingRouteSupport.requireSubscriptionCurrent(repository, workspaceId);
        List<SubjectRow> catalog = subjects.stream().map(this::requireCatalogSubject).toList();
        UUID courseId = UUID.randomUUID();
        repository.insertCourse(courseId, workspaceId, classId, trimmed);
        for (SubjectRow subject : catalog) {
            repository.insertCourseSubject(
                    UUID.randomUUID(), workspaceId, classId, courseId, subject.id());
        }
        return view(
                new CourseRow(courseId, workspaceId, classId, trimmed, "ACTIVE", 0),
                repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /** Một khóa trong đúng workspace kèm môn hiện hành; khóa ngoài workspace trả 404. */
    @Transactional(readOnly = true)
    public CourseView getCourse(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        CourseRow row =
                repository.findCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        return view(row, repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    @Transactional(readOnly = true)
    public TeachingRepository.Page<CourseView> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        TeachingRepository.Page<CourseRow> page =
                repository.pageCourses(workspaceId, classId, cursor, limit);
        Map<UUID, List<CourseSubjectRow>> subjects =
                repository.currentSubjectsByCourse(
                        workspaceId, page.items().stream().map(row -> row.id()).toList());
        return page.map(row -> view(row, subjects.getOrDefault(row.id(), List.of())));
    }

    /** Các khóa hiện hành của một lớp trong workspace, cũ nhất trước, kèm môn hiện hành. */
    @Transactional(readOnly = true)
    public List<CourseView> listCourses(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        List<CourseRow> rows = repository.listCourses(workspaceId, classId);
        Map<UUID, List<CourseSubjectRow>> subjects =
                repository.currentSubjectsByCourse(
                        workspaceId, rows.stream().map(row -> row.id()).toList());
        return rows.stream()
                .map(row -> view(row, subjects.getOrDefault(row.id(), List.of())))
                .toList();
    }

    /**
     * Đổi tên khóa hiện hành; khóa đã thu hồi bị trigger giữ lịch sử khóa mọi thay đổi nên 409. Như
     * đổi tên lớp, bắt buộc If-Match khớp row version hiện tại, kiểm tra sau khi khóa row và trước
     * khi ghi.
     */
    @Transactional
    public CourseView renameCourse(
            UUID workspaceId, UUID courseId, String name, @Nullable String ifMatch) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        String trimmed = normalizeName(name);
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "COURSE_ALREADY_REVOKED", "Khóa đã bị thu hồi trước đó.");
        }
        requireClassActive(workspaceId, row.classId());
        IdempotencyService.requireIfMatch(ifMatch, row.version());
        repository.renameCourse(courseId, trimmed);
        return view(
                new CourseRow(
                        row.id(),
                        row.workspaceId(),
                        row.classId(),
                        trimmed,
                        row.state(),
                        row.version() + 1),
                repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /**
     * Thu hồi khóa: đổi state của khóa và đóng luôn ghi danh, phân công giảng dạy phụ thuộc các
     * môn–khóa của khóa trong cùng transaction qua {@link TeachingRevocationContributor}, giữ
     * nguyên toàn bộ lịch sử; không bị chặn bởi gói hết hạn.
     */
    @Transactional
    public CourseView revokeCourse(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "COURSE_ALREADY_REVOKED", "Khóa đã bị thu hồi trước đó.");
        }
        repository.revokeCourse(courseId);
        revocationContributor.revokeDependentsOfCourse(workspaceId, courseId);
        return view(
                new CourseRow(
                        row.id(),
                        row.workspaceId(),
                        row.classId(),
                        row.name(),
                        "REVOKED",
                        row.version() + 1),
                repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /**
     * Gắn thêm môn vào khóa theo ngữ nghĩa hợp tập: môn đã hiện hành thì giữ nguyên, chưa có thì
     * tạo dòng môn–khóa ACTIVE mới. Chỉ tạo dòng môn–khóa — không tạo ghi danh hay phân công nào
     * cho môn vừa gắn.
     */
    @Transactional
    public CourseView addSubjects(UUID workspaceId, UUID courseId, List<UUID> subjectIds) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        List<UUID> subjects = requireSubjects(subjectIds);
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        boolean courseRevoked = "REVOKED".equals(row.state());
        if (courseRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_NOT_ACTIVE",
                    "Khóa đã bị thu hồi, không gắn thêm môn được nữa.");
        }
        requireClassActive(workspaceId, row.classId());
        TeachingRouteSupport.requireSubscriptionCurrent(repository, workspaceId);
        List<SubjectRow> catalog = subjects.stream().map(this::requireCatalogSubject).toList();
        for (SubjectRow subject : catalog) {
            if (repository
                    .findCurrentCourseSubject(workspaceId, courseId, subject.id())
                    .isEmpty()) {
                repository.insertCourseSubject(
                        UUID.randomUUID(), workspaceId, row.classId(), courseId, subject.id());
            }
        }
        return view(row, repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /**
     * Gỡ một môn khỏi khóa: thu hồi dòng môn–khóa và đóng luôn ghi danh/phân công giảng dạy phụ
     * thuộc dòng môn–khóa đó trong cùng transaction qua {@link TeachingRevocationContributor}, giữ
     * lịch sử. Khóa được phép còn lại ít môn hơn lúc tạo, kể cả rỗng.
     */
    @Transactional
    public CourseView revokeCourseSubject(UUID workspaceId, UUID courseId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        CourseSubjectRow subject =
                repository
                        .lockCourseSubject(workspaceId, courseId, courseSubjectId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "COURSE_SUBJECT_NOT_FOUND",
                                        "Không tìm thấy môn trong khóa này."));
        boolean subjectRevoked = "REVOKED".equals(subject.state());
        if (subjectRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_SUBJECT_ALREADY_REVOKED",
                    "Môn đã được gỡ khỏi khóa trước đó.");
        }
        repository.revokeCourseSubject(courseSubjectId);
        revocationContributor.revokeDependentsOfCourseSubject(workspaceId, courseSubjectId);
        return view(row, repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /** Danh mục môn tham chiếu đang ACTIVE; danh mục không chứa dữ liệu riêng của workspace nào. */
    @Transactional(readOnly = true)
    public List<SubjectView> listSubjects() {
        return repository.listSubjects().stream()
                .map(subject -> new SubjectView(subject.id(), subject.code(), subject.name()))
                .toList();
    }

    private void requireClassActive(UUID workspaceId, UUID classId) {
        var clazz =
                repository
                        .findClass(workspaceId, classId)
                        .orElseThrow(CourseService::classNotFound);
        if (!"ACTIVE".equals(clazz.state())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "CLASS_NOT_ACTIVE",
                    "Lớp đã bị thu hồi, không mở rộng hoặc sửa khóa được nữa.");
        }
    }

    private List<UUID> requireSubjects(List<UUID> subjectIds) {
        Objects.requireNonNull(subjectIds, "subjectIds");
        List<UUID> subjects = List.copyOf(new LinkedHashSet<>(subjectIds));
        if (subjects.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "COURSE_REQUIRES_SUBJECTS",
                    "Khóa phải có ít nhất một môn.");
        }
        return subjects;
    }

    private SubjectRow requireCatalogSubject(UUID subjectId) {
        SubjectRow subject = repository.findSubject(subjectId).orElseThrow(() -> subjectNotFound());
        if (!"ACTIVE".equals(subject.state())) {
            throw subjectNotFound();
        }
        return subject;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COURSE_NAME",
                    "Tên khóa phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND", "Không tìm thấy lớp trong workspace này.");
    }

    private static HttpProblemException courseNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "COURSE_NOT_FOUND",
                "Không tìm thấy khóa trong workspace này.");
    }

    private static HttpProblemException subjectNotFound() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "SUBJECT_NOT_FOUND",
                "Môn không có trong danh mục đang hoạt động.");
    }

    private static CourseView view(CourseRow row, List<CourseSubjectRow> subjects) {
        return new CourseView(
                row.id(),
                row.classId(),
                row.name(),
                subjects.stream()
                        .map(subject ->
                                new SubjectEntry(
                                        subject.id(), subject.subjectId(), subject.subjectName()))
                        .toList(),
                row.version());
    }
}
