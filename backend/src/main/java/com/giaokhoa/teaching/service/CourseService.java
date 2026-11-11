package com.giaokhoa.teaching.service;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingRevocationContributor;
import com.giaokhoa.teaching.model.Course;
import com.giaokhoa.teaching.model.CourseSubject;
import com.giaokhoa.teaching.model.Subject;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.SubjectRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository.Page;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * gắn/gỡ môn của khóa, đọc danh mục môn tham chiếu. Mọi thao tác đi qua các repository đã khóa phạm
 * vi theo workspace và lớp cha. Như {@link ClassService}, mỗi phương thức có phạm vi workspace đặt
 * context workspace cho transaction qua {@link WorkspaceTransaction} ngay khi bắt đầu để truy vấn
 * chạy đúng trong schema tenant cùng phạm vi; riêng danh mục môn là dữ liệu dùng chung, không nằm
 * trong schema tenant nên không cần context. Mọi phương thức ghi giữ khóa workspace thống nhất qua
 * {@link ResourceMutationCoordinator} ngay sau khi đặt context, như {@link ClassService}.
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
    private static final String ACTIVE = "ACTIVE";
    private static final List<String> CURRENT_STATES = List.of("PENDING", ACTIVE);

    private final SchoolClassRepository schoolClasses;
    private final CourseRepository courses;
    private final CourseSubjectRepository courseSubjects;
    private final SubjectRepository subjects;
    private final TeachingPageRepository pages;
    private final TeachingScopePreconditions preconditions;
    private final WorkspaceTransaction workspaceTransaction;
    private final ResourceMutationCoordinator coordinator;
    private final TeachingRevocationContributor revocationContributor;

    public CourseService(
            SchoolClassRepository schoolClasses,
            CourseRepository courses,
            CourseSubjectRepository courseSubjects,
            SubjectRepository subjects,
            TeachingPageRepository pages,
            TeachingScopePreconditions preconditions,
            WorkspaceTransaction workspaceTransaction,
            ResourceMutationCoordinator coordinator,
            TeachingRevocationContributor revocationContributor) {
        this.schoolClasses = schoolClasses;
        this.courses = courses;
        this.courseSubjects = courseSubjects;
        this.subjects = subjects;
        this.pages = pages;
        this.preconditions = preconditions;
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
        List<UUID> requested = requireSubjects(subjectIds);
        var clazz =
                schoolClasses
                        .lockByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.getState());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.CLASS_NOT_ACTIVE,
                    "Lớp đã bị thu hồi, không tạo khóa được nữa.");
        }
        preconditions.requireSubscriptionCurrent(workspaceId);
        List<Subject> catalog = requested.stream().map(this::requireCatalogSubject).toList();
        UUID courseId = UUID.randomUUID();
        Course course = courses.save(new Course(courseId, workspaceId, classId, trimmed));
        for (Subject subject : catalog) {
            courseSubjects.save(
                    new CourseSubject(
                            UUID.randomUUID(), workspaceId, classId, courseId, subject.getId()));
        }
        return view(course, course.getVersion(), listCurrentSubjectEntries(workspaceId, courseId));
    }

    /** Một khóa trong đúng workspace kèm môn hiện hành; khóa ngoài workspace trả 404. */
    @Transactional(readOnly = true)
    public CourseView getCourse(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        Course row =
                courses.findByWorkspaceIdAndId(workspaceId, courseId)
                        .orElseThrow(() -> courseNotFound());
        return view(row, row.getVersion(), listCurrentSubjectEntries(workspaceId, courseId));
    }

    @Transactional(readOnly = true)
    public Page<CourseView> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        var page = pages.pageCourses(workspaceId, classId, cursor, limit);
        Map<UUID, List<SubjectEntry>> subjects =
                subjectsByCourse(
                        workspaceId, page.items().stream().map(item -> item.getId()).toList());
        return page.map(item ->
                new CourseView(
                        item.getId(),
                        item.getClassId(),
                        item.getName(),
                        subjects.getOrDefault(item.getId(), List.of()),
                        item.getVersion()));
    }

    /** Các khóa hiện hành của một lớp trong workspace, cũ nhất trước, kèm môn hiện hành. */
    @Transactional(readOnly = true)
    public List<CourseView> listCourses(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        List<Course> rows =
                courses.findByWorkspaceIdAndClassIdAndStateOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, ACTIVE);
        Map<UUID, List<SubjectEntry>> subjects =
                subjectsByCourse(workspaceId, rows.stream().map(Course::getId).toList());
        return rows.stream()
                .map(row ->
                        view(row, row.getVersion(), subjects.getOrDefault(row.getId(), List.of())))
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
        Course row =
                courses.lockByWorkspaceIdAndId(workspaceId, courseId)
                        .orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.COURSE_ALREADY_REVOKED,
                    "Khóa đã bị thu hồi trước đó.");
        }
        requireClassActive(workspaceId, row.getClassId());
        IdempotencyService.requireIfMatch(ifMatch, row.getVersion());
        row.rename(trimmed);
        return view(row, row.getVersion() + 1, listCurrentSubjectEntries(workspaceId, courseId));
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
        Course row =
                courses.lockByWorkspaceIdAndId(workspaceId, courseId)
                        .orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.COURSE_ALREADY_REVOKED,
                    "Khóa đã bị thu hồi trước đó.");
        }
        row.revoke();
        revocationContributor.revokeDependentsOfCourse(workspaceId, courseId);
        return view(row, row.getVersion() + 1, listCurrentSubjectEntries(workspaceId, courseId));
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
        List<UUID> requested = requireSubjects(subjectIds);
        Course row =
                courses.lockByWorkspaceIdAndId(workspaceId, courseId)
                        .orElseThrow(() -> courseNotFound());
        boolean courseRevoked = "REVOKED".equals(row.getState());
        if (courseRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.COURSE_NOT_ACTIVE,
                    "Khóa đã bị thu hồi, không gắn thêm môn được nữa.");
        }
        requireClassActive(workspaceId, row.getClassId());
        preconditions.requireSubscriptionCurrent(workspaceId);
        List<Subject> catalog = requested.stream().map(this::requireCatalogSubject).toList();
        for (Subject subject : catalog) {
            if (courseSubjects
                    .findByWorkspaceIdAndCourseIdAndSubjectIdAndStateIn(
                            workspaceId, courseId, subject.getId(), CURRENT_STATES)
                    .isEmpty()) {
                courseSubjects.save(
                        new CourseSubject(
                                UUID.randomUUID(),
                                workspaceId,
                                row.getClassId(),
                                courseId,
                                subject.getId()));
            }
        }
        return view(row, row.getVersion(), listCurrentSubjectEntries(workspaceId, courseId));
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
        Course row =
                courses.lockByWorkspaceIdAndId(workspaceId, courseId)
                        .orElseThrow(() -> courseNotFound());
        CourseSubject subject =
                courseSubjects
                        .lockByWorkspaceIdAndCourseIdAndId(workspaceId, courseId, courseSubjectId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        ErrorCodes.COURSE_SUBJECT_NOT_FOUND,
                                        "Không tìm thấy môn trong khóa này."));
        boolean subjectRevoked = "REVOKED".equals(subject.getState());
        if (subjectRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.COURSE_SUBJECT_ALREADY_REVOKED,
                    "Môn đã được gỡ khỏi khóa trước đó.");
        }
        subject.revoke();
        revocationContributor.revokeDependentsOfCourseSubject(workspaceId, courseSubjectId);
        return view(row, row.getVersion(), listCurrentSubjectEntries(workspaceId, courseId));
    }

    /** Danh mục môn tham chiếu đang ACTIVE; danh mục không chứa dữ liệu riêng của workspace nào. */
    @Transactional(readOnly = true)
    public List<SubjectView> listSubjects() {
        return subjects.findByStateOrderByCodeAsc(ACTIVE).stream()
                .map(subject ->
                        new SubjectView(subject.getId(), subject.getCode(), subject.getName()))
                .toList();
    }

    /** Các môn hiện hành của một khóa kèm tên danh mục, theo mã môn rồi tới id của dòng. */
    private List<SubjectEntry> listCurrentSubjectEntries(UUID workspaceId, UUID courseId) {
        List<CourseSubject> rows =
                courseSubjects.findByWorkspaceIdAndCourseIdAndStateIn(
                        workspaceId, courseId, CURRENT_STATES);
        return subjectEntries(rows, catalogById(rows));
    }

    /** Nạp môn của tối đa một trang khóa bằng một truy vấn thay vì một query cho mỗi khóa. */
    private Map<UUID, List<SubjectEntry>> subjectsByCourse(UUID workspaceId, List<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        List<CourseSubject> rows =
                courseSubjects.findByWorkspaceIdAndCourseIdInAndStateIn(
                        workspaceId, courseIds, CURRENT_STATES);
        Map<UUID, Subject> catalog = catalogById(rows);
        Map<UUID, List<CourseSubject>> byCourse = new LinkedHashMap<>();
        for (CourseSubject row : rows) {
            byCourse.computeIfAbsent(row.getCourseId(), _ -> new ArrayList<>()).add(row);
        }
        Map<UUID, List<SubjectEntry>> entries = new LinkedHashMap<>();
        byCourse.forEach((courseId, list) -> entries.put(courseId, subjectEntries(list, catalog)));
        return entries;
    }

    private Map<UUID, Subject> catalogById(List<CourseSubject> rows) {
        Map<UUID, Subject> catalog = new HashMap<>();
        subjects.findAllById(rows.stream().map(CourseSubject::getSubjectId).distinct().toList())
                .forEach(subject -> catalog.put(subject.getId(), subject));
        return catalog;
    }

    private static List<SubjectEntry> subjectEntries(
            List<CourseSubject> rows, Map<UUID, Subject> catalog) {
        // Tên và mã môn thuộc danh mục hệ thống: phân giải theo lô từ ID rồi sắp xếp ở tầng
        // ứng dụng theo đúng thứ tự (mã môn, id dòng) của tầng JDBC cũ.
        return rows.stream()
                .filter(row -> catalog.containsKey(row.getSubjectId()))
                .sorted(
                        Comparator.comparing((CourseSubject row) ->
                                        Objects.requireNonNull(catalog.get(row.getSubjectId()))
                                                .getCode())
                                .thenComparing(CourseSubject::getId))
                .map(row ->
                        new SubjectEntry(
                                row.getId(),
                                row.getSubjectId(),
                                Objects.requireNonNull(catalog.get(row.getSubjectId())).getName()))
                .toList();
    }

    private void requireClassActive(UUID workspaceId, UUID classId) {
        var clazz =
                schoolClasses
                        .findByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(CourseService::classNotFound);
        if (!ACTIVE.equals(clazz.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.CLASS_NOT_ACTIVE,
                    "Lớp đã bị thu hồi, không mở rộng hoặc sửa khóa được nữa.");
        }
    }

    private List<UUID> requireSubjects(List<UUID> subjectIds) {
        Objects.requireNonNull(subjectIds, "subjectIds");
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(subjectIds));
        if (requested.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.COURSE_REQUIRES_SUBJECTS,
                    "Khóa phải có ít nhất một môn.");
        }
        return requested;
    }

    private Subject requireCatalogSubject(UUID subjectId) {
        Subject subject = subjects.findById(subjectId).orElseThrow(() -> subjectNotFound());
        if (!ACTIVE.equals(subject.getState())) {
            throw subjectNotFound();
        }
        return subject;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_COURSE_NAME,
                    "Tên khóa phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.CLASS_NOT_FOUND,
                "Không tìm thấy lớp trong workspace này.");
    }

    private static HttpProblemException courseNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.COURSE_NOT_FOUND,
                "Không tìm thấy khóa trong workspace này.");
    }

    private static HttpProblemException subjectNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.SUBJECT_NOT_FOUND,
                "Môn không có trong danh mục đang hoạt động.");
    }

    private static CourseView view(Course row, long version, List<SubjectEntry> subjects) {
        return new CourseView(row.getId(), row.getClassId(), row.getName(), subjects, version);
    }
}
