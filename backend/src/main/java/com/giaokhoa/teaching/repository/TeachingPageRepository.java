package com.giaokhoa.teaching.repository;

import com.giaokhoa.shared.http.HttpProblemException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn phân trang keyset của giảng dạy trên các repository aggregate: cursor là UUID của hàng
 * trước (kể cả khi hàng đó đã thu hồi — mốc của nó được tra riêng, bất kể trạng thái, và phải thuộc
 * đúng workspace cùng parent của danh sách đang đọc, lệch thì 400 như tầng JDBC cũ); trang được đọc
 * bằng {@link Window}/{@link ScrollPosition} theo khóa (created_at, id) và chỉ trả closed
 * projection, không trả entity ra ngoài.
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
        ScrollPosition position =
                position(
                        cursor,
                        id -> schoolClasses.findCreatedAtByWorkspaceIdAndId(workspaceId, id));
        Window<ClassListItem> window;
        if (homeroomTeacherId == null) {
            window =
                    schoolClasses.findByWorkspaceIdAndStateOrderByCreatedAtAscIdAsc(
                            workspaceId, ACTIVE, position, Limit.of(pageSize(limit)));
        } else {
            // Lọc theo chủ nhiệm được phân giải trong cùng mặt phẳng trước khi chọn trang,
            // thay EXISTS lồng của tầng JDBC cũ.
            List<UUID> classIds =
                    homeroomAssignments.findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                            workspaceId, homeroomTeacherId, CURRENT_STATES);
            window =
                    schoolClasses.findByWorkspaceIdAndStateAndIdInOrderByCreatedAtAscIdAsc(
                            workspaceId, ACTIVE, classIds, position, Limit.of(pageSize(limit)));
        }
        return toPage(window, ClassListItem::getId);
    }

    public Page<RosterListItem> pageRoster(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        ScrollPosition position =
                position(
                        cursor,
                        id ->
                                classStudents.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        Window<RosterListItem> window =
                classStudents.findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, CURRENT_STATES, position, Limit.of(pageSize(limit)));
        return toPage(window, RosterListItem::getId);
    }

    public Page<CourseListItem> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        ScrollPosition position =
                position(
                        cursor,
                        id ->
                                courses.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        Window<CourseListItem> window =
                courses.findByWorkspaceIdAndClassIdAndStateOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, ACTIVE, position, Limit.of(pageSize(limit)));
        return toPage(window, CourseListItem::getId);
    }

    public Page<EnrollmentListItem> pageEnrollments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        ScrollPosition position =
                position(
                        cursor,
                        id ->
                                enrollments.findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
                                        workspaceId, courseSubjectId, id));
        Window<EnrollmentListItem> window =
                enrollments.findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId,
                        courseSubjectId,
                        CURRENT_STATES,
                        position,
                        Limit.of(pageSize(limit)));
        return toPage(window, EnrollmentListItem::getId);
    }

    public Page<TeachingAssignmentListItem> pageTeachingAssignments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        ScrollPosition position =
                position(
                        cursor,
                        id ->
                                teachingAssignments
                                        .findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
                                                workspaceId, courseSubjectId, id));
        Window<TeachingAssignmentListItem> window =
                teachingAssignments
                        .findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
                                workspaceId,
                                courseSubjectId,
                                CURRENT_STATES,
                                position,
                                Limit.of(pageSize(limit)));
        return toPage(window, TeachingAssignmentListItem::getId);
    }

    public Page<HomeroomAssignmentListItem> pageHomeroomAssignments(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        ScrollPosition position =
                position(
                        cursor,
                        id ->
                                homeroomAssignments.findCreatedAtByWorkspaceIdAndClassIdAndId(
                                        workspaceId, classId, id));
        Window<HomeroomAssignmentListItem> window =
                homeroomAssignments.findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, CURRENT_STATES, position, Limit.of(pageSize(limit)));
        return toPage(window, HomeroomAssignmentListItem::getId);
    }

    private static <T> Page<T> toPage(Window<T> window, Function<T, UUID> idOf) {
        List<T> items = window.getContent();
        return new Page<>(
                items,
                window.hasNext() && !items.isEmpty()
                        ? idOf.apply(items.getLast()).toString()
                        : null);
    }

    private static ScrollPosition position(
            @Nullable String cursor, Function<UUID, Optional<Instant>> anchorLookup) {
        if (cursor == null) {
            return ScrollPosition.offset();
        }
        UUID cursorId = parseCursorId(cursor);
        Instant createdAt = anchorLookup.apply(cursorId).orElseThrow(() -> invalidCursor());
        return ScrollPosition.forward(Map.of("createdAt", createdAt, "id", cursorId));
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
