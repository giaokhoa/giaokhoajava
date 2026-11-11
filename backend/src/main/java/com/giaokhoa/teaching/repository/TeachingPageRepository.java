package com.giaokhoa.teaching.repository;

import com.giaokhoa.shared.http.HttpProblemException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn phân trang keyset của giảng dạy trên các repository aggregate: cursor là UUID của hàng
 * trước (kể cả khi hàng đó đã thu hồi — mốc của nó được tra riêng, bất kể trạng thái, và phải thuộc
 * đúng workspace cùng parent của danh sách đang đọc, lệch thì 400 như tầng JDBC cũ). Mốc keyset
 * (created_at, id) được áp bằng điều kiện tường minh trong truy vấn và người đọc lấy thừa một hàng
 * để biết còn trang sau, cùng cách tầng membership vẫn dùng: ScrollPosition của Spring Data không
 * được áp cho truy vấn trả interface projection — vị trí bị bỏ qua lặng lẽ nên trang sau lặp lại
 * trang đầu (đã kiểm chứng trên spring-data-jpa 4.1.2/Hibernate 7.4 với cả hai dạng trả về: entity
 * thì đúng, projection thì sai).
 */
@Repository
public class TeachingPageRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final List<String> CURRENT_STATES = List.of("PENDING", ACTIVE);

    private final SchoolClassRepository schoolClasses;
    private final ClassStudentRepository classStudents;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final TeachingAssignmentRepository teachingAssignments;
    private final HomeroomAssignmentRepository homeroomAssignments;

    public TeachingPageRepository(
            SchoolClassRepository schoolClasses,
            ClassStudentRepository classStudents,
            CourseRepository courses,
            EnrollmentRepository enrollments,
            TeachingAssignmentRepository teachingAssignments,
            HomeroomAssignmentRepository homeroomAssignments) {
        this.schoolClasses = schoolClasses;
        this.classStudents = classStudents;
        this.courses = courses;
        this.enrollments = enrollments;
        this.teachingAssignments = teachingAssignments;
        this.homeroomAssignments = homeroomAssignments;
    }

    /** Page cursor là UUID của hàng trước, kể cả khi hàng đó đã được thu hồi. */
    public record Page<T>(List<T> items, @Nullable String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }

        public <R> Page<R> map(Function<T, R> mapping) {
            return new Page<>(items.stream().map(mapping).toList(), nextCursor);
        }
    }

    /** Kiểm tra còn ít nhất một lớp ACTIVE được gắn với chủ nhiệm (không đọc cả roster). */
    public boolean hasCurrentHomeroomClass(UUID workspaceId, UUID teacherUserId) {
        List<UUID> classIds =
                homeroomAssignments.findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                        workspaceId, teacherUserId, CURRENT_STATES);
        return !classIds.isEmpty()
                && schoolClasses.existsByWorkspaceIdAndIdInAndState(workspaceId, classIds, ACTIVE);
    }

    public Page<ClassListItem> pageClasses(
            UUID workspaceId,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable UUID homeroomTeacherId) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id -> schoolClasses.findCreatedAtByWorkspaceIdAndId(workspaceId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<ClassListItem> fetched;
        if (homeroomTeacherId == null) {
            fetched =
                    anchor == null
                            ? schoolClasses.findFirstPage(workspaceId, ACTIVE, request)
                            : schoolClasses.findPageAfter(
                                    workspaceId, ACTIVE, anchor.createdAt(), anchor.id(), request);
        } else {
            // Lọc theo chủ nhiệm được phân giải trong cùng mặt phẳng trước khi chọn trang,
            // thay EXISTS lồng của tầng JDBC cũ.
            List<UUID> classIds =
                    homeroomAssignments.findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                            workspaceId, homeroomTeacherId, CURRENT_STATES);
            fetched =
                    anchor == null
                            ? schoolClasses.findFirstPageByIds(
                                    workspaceId, ACTIVE, classIds, request)
                            : schoolClasses.findPageAfterByIds(
                                    workspaceId,
                                    ACTIVE,
                                    classIds,
                                    anchor.createdAt(),
                                    anchor.id(),
                                    request);
        }
        return toPage(fetched, size, row -> row.getId());
    }

    public Page<RosterListItem> pageRoster(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id ->
                                classStudents.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<RosterListItem> fetched =
                anchor == null
                        ? classStudents.findFirstPage(workspaceId, classId, CURRENT_STATES, request)
                        : classStudents.findPageAfter(
                                workspaceId,
                                classId,
                                CURRENT_STATES,
                                anchor.createdAt(),
                                anchor.id(),
                                request);
        return toPage(fetched, size, row -> row.getId());
    }

    public Page<CourseListItem> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id ->
                                courses.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<CourseListItem> fetched =
                anchor == null
                        ? courses.findFirstPage(workspaceId, classId, ACTIVE, request)
                        : courses.findPageAfter(
                                workspaceId,
                                classId,
                                ACTIVE,
                                anchor.createdAt(),
                                anchor.id(),
                                request);
        return toPage(fetched, size, row -> row.getId());
    }

    public Page<EnrollmentListItem> pageEnrollments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id ->
                                enrollments.findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
                                        workspaceId, courseSubjectId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<EnrollmentListItem> fetched =
                anchor == null
                        ? enrollments.findFirstPage(
                                workspaceId, courseSubjectId, CURRENT_STATES, request)
                        : enrollments.findPageAfter(
                                workspaceId,
                                courseSubjectId,
                                CURRENT_STATES,
                                anchor.createdAt(),
                                anchor.id(),
                                request);
        return toPage(fetched, size, row -> row.getId());
    }

    public Page<TeachingAssignmentListItem> pageTeachingAssignments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id ->
                                teachingAssignments
                                        .findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
                                                workspaceId, courseSubjectId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<TeachingAssignmentListItem> fetched =
                anchor == null
                        ? teachingAssignments.findFirstPage(
                                workspaceId, courseSubjectId, CURRENT_STATES, request)
                        : teachingAssignments.findPageAfter(
                                workspaceId,
                                courseSubjectId,
                                CURRENT_STATES,
                                anchor.createdAt(),
                                anchor.id(),
                                request);
        return toPage(fetched, size, row -> row.getId());
    }

    public Page<HomeroomAssignmentListItem> pageHomeroomAssignments(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        int size = pageSize(limit);
        Anchor anchor =
                anchor(
                        cursor,
                        id ->
                                homeroomAssignments.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        PageRequest request = PageRequest.of(0, size + 1);
        List<HomeroomAssignmentListItem> fetched =
                anchor == null
                        ? homeroomAssignments.findFirstPage(
                                workspaceId, classId, CURRENT_STATES, request)
                        : homeroomAssignments.findPageAfter(
                                workspaceId,
                                classId,
                                CURRENT_STATES,
                                anchor.createdAt(),
                                anchor.id(),
                                request);
        return toPage(fetched, size, row -> row.getId());
    }

    /** Mốc keyset của hàng cursor: thời điểm tạo của nó cùng chính id của nó. */
    private record Anchor(Instant createdAt, UUID id) {}

    private static @Nullable Anchor anchor(
            @Nullable String cursor, Function<UUID, Optional<Instant>> anchorLookup) {
        if (cursor == null) {
            return null;
        }
        UUID cursorId = parseCursorId(cursor);
        Instant createdAt = anchorLookup.apply(cursorId).orElseThrow(() -> invalidCursor());
        return new Anchor(createdAt, cursorId);
    }

    private static <T> Page<T> toPage(List<T> fetched, int pageSize, Function<T, UUID> idOf) {
        if (fetched.size() > pageSize) {
            List<T> items = fetched.subList(0, pageSize);
            return new Page<>(items, idOf.apply(items.getLast()).toString());
        }
        return new Page<>(fetched, null);
    }

    private static UUID parseCursorId(String cursor) {
        try {
            UUID cursorId = UUID.fromString(cursor);
            if (!cursorId.toString().equals(cursor)) {
                throw invalidCursor();
            }
            return cursorId;
        } catch (IllegalArgumentException ex) {
            throw invalidCursor(ex);
        }
    }

    private static int pageSize(@Nullable Integer limit) {
        return limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    }

    /** Cursor không thuộc đúng danh sách đang đọc thì 400 như các đường đọc phân trang khác. */
    private static HttpProblemException invalidCursor() {
        return invalidCursor(null);
    }

    private static HttpProblemException invalidCursor(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Cursor danh sách không hợp lệ.", cause);
    }
}
