package com.giaokhoa.teaching;

import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.CourseRow;
import com.giaokhoa.teaching.data.TeachingRepository.CourseSubjectRow;
import com.giaokhoa.teaching.data.TeachingRepository.SubjectRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ khóa, môn của khóa và danh mục môn ở tầng dữ kiện nguồn: tạo/đổi tên/thu hồi khóa,
 * gắn/gỡ môn của khóa, đọc danh mục môn tham chiếu. Mọi thao tác đi qua {@link TeachingRepository}
 * đã khóa phạm vi theo workspace và lớp cha.
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

    public CourseService(TeachingRepository repository) {
        this.repository = repository;
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
        requireSubscriptionCurrent(workspaceId);
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
        CourseRow row =
                repository.findCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        return view(row, repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /** Các khóa hiện hành của một lớp trong workspace, cũ nhất trước, kèm môn hiện hành. */
    @Transactional(readOnly = true)
    public List<CourseView> listCourses(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        return repository.listCourses(workspaceId, classId).stream()
                .map(row -> view(row, repository.listCurrentCourseSubjects(workspaceId, row.id())))
                .toList();
    }

    /** Đổi tên khóa hiện hành; khóa đã thu hồi bị trigger giữ lịch sử khóa mọi thay đổi nên 409. */
    @Transactional
    public CourseView renameCourse(UUID workspaceId, UUID courseId, String name) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        String trimmed = normalizeName(name);
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "COURSE_ALREADY_REVOKED", "Khóa đã bị thu hồi trước đó.");
        }
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

    /** Thu hồi khóa: chỉ đổi state, giữ lịch sử; không bị chặn bởi gói hết hạn. */
    @Transactional
    public CourseView revokeCourse(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        CourseRow row =
                repository.lockCourse(workspaceId, courseId).orElseThrow(() -> courseNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "COURSE_ALREADY_REVOKED", "Khóa đã bị thu hồi trước đó.");
        }
        repository.revokeCourse(courseId);
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
        requireSubscriptionCurrent(workspaceId);
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
     * Gỡ một môn khỏi khóa: thu hồi dòng môn–khóa, giữ lịch sử. Khóa được phép còn lại ít môn hơn
     * lúc tạo, kể cả rỗng. Việc đóng ghi danh/phân công phụ thuộc dòng môn–khóa này ở tầng policy
     * thuộc lớp projection riêng.
     */
    @Transactional
    public CourseView revokeCourseSubject(UUID workspaceId, UUID courseId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
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
        return view(row, repository.listCurrentCourseSubjects(workspaceId, courseId));
    }

    /** Danh mục môn tham chiếu đang ACTIVE; danh mục không chứa dữ liệu riêng của workspace nào. */
    @Transactional(readOnly = true)
    public List<SubjectView> listSubjects() {
        return repository.listSubjects().stream()
                .map(subject -> new SubjectView(subject.id(), subject.code(), subject.name()))
                .toList();
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
