package com.giaokhoa.teaching.data;

import static com.giaokhoa.teaching.data.TeachingRepository.mapClass;
import static com.giaokhoa.teaching.data.TeachingRepository.mapCourse;
import static com.giaokhoa.teaching.data.TeachingRepository.mapHomeroomAssignment;
import static com.giaokhoa.teaching.data.TeachingRepository.mapRoster;
import static com.giaokhoa.teaching.data.TeachingRepository.mapTeachingAssignment;
import static com.giaokhoa.teaching.data.TeachingRepository.rosterSelect;

import com.giaokhoa.teaching.data.TeachingRepository.ClassRow;
import com.giaokhoa.teaching.data.TeachingRepository.CourseRow;
import com.giaokhoa.teaching.data.TeachingRepository.EnrollmentListRow;
import com.giaokhoa.teaching.data.TeachingRepository.HomeroomAssignmentRow;
import com.giaokhoa.teaching.data.TeachingRepository.Page;
import com.giaokhoa.teaching.data.TeachingRepository.RosterRow;
import com.giaokhoa.teaching.data.TeachingRepository.TeachingAssignmentRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Truy vấn phân trang chỉ đọc, tách khỏi các thao tác ghi dữ kiện giảng dạy. */
final class TeachingPageQueries {
    private final JdbcTemplate jdbc;

    TeachingPageQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keyset pagination cho các bảng có created_at/id bất biến và giữ hàng REVOKED làm lịch sử. SQL
     * và tên bảng/alias chỉ đến từ các phương thức repository dưới đây, không từ HTTP.
     */
    private <T> Page<T> page(
            String table,
            String alias,
            String query,
            RowMapper<T> mapper,
            Function<T, UUID> idOf,
            UUID workspaceId,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable String anchorParentColumn,
            @Nullable UUID anchorParentId,
            Object... queryArgs) {
        int pageSize = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        UUID cursorId = null;
        OffsetDateTime createdAt = null;
        if (cursor != null) {
            try {
                cursorId = UUID.fromString(cursor);
                if (!cursorId.toString().equals(cursor)) {
                    return new Page<>(List.of(), null);
                }
            } catch (IllegalArgumentException ex) {
                return new Page<>(List.of(), null);
            }
            // Cursor thuộc lớp/môn–khóa khác không được làm dịch trang của danh sách hiện tại.
            String anchorSql =
                    "SELECT created_at FROM "
                            + table
                            + " WHERE workspace_id = ? AND id = ?"
                            + (anchorParentColumn == null
                                    ? ""
                                    : " AND " + anchorParentColumn + " = ?");
            List<OffsetDateTime> anchors =
                    anchorParentColumn == null
                            ? jdbc.queryForList(
                                    anchorSql, OffsetDateTime.class, workspaceId, cursorId)
                            : jdbc.queryForList(
                                    anchorSql,
                                    OffsetDateTime.class,
                                    workspaceId,
                                    cursorId,
                                    anchorParentId);
            if (anchors.isEmpty()) {
                return new Page<>(List.of(), null);
            }
            createdAt = anchors.getFirst();
        }
        List<Object> args = new ArrayList<>(Arrays.asList(queryArgs));
        String sql = query;
        if (cursorId != null) {
            sql += " AND (" + alias + ".created_at, " + alias + ".id) > (?, ?)";
            args.add(createdAt);
            args.add(cursorId);
        }
        sql += " ORDER BY " + alias + ".created_at, " + alias + ".id LIMIT ?";
        args.add(pageSize + 1);
        List<T> rows = jdbc.query(sql, mapper, args.toArray());
        boolean hasMore = rows.size() > pageSize;
        List<T> items = List.copyOf(rows.subList(0, Math.min(rows.size(), pageSize)));
        return new Page<>(
                items, hasMore && !items.isEmpty() ? idOf.apply(items.getLast()).toString() : null);
    }

    /** Kiểm tra còn ít nhất một lớp ACTIVE được gắn với chủ nhiệm (không đọc cả roster). */
    public boolean hasCurrentHomeroomClass(UUID workspaceId, UUID teacherUserId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM school_class c"
                                + " JOIN homeroom_assignment h"
                                + " ON h.workspace_id = c.workspace_id AND h.class_id = c.id"
                                + " WHERE c.workspace_id = ? AND c.state = 'ACTIVE'"
                                + " AND h.teacher_user_id = ?"
                                + " AND h.state IN ('PENDING', 'ACTIVE'))",
                        Boolean.class,
                        workspaceId,
                        teacherUserId));
    }

    public Page<ClassRow> pageClasses(
            UUID workspaceId,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable UUID homeroomTeacherId) {
        String query =
                "SELECT c.id, c.workspace_id, c.name, c.state, c.version"
                        + " FROM school_class c WHERE c.workspace_id = ? AND c.state = 'ACTIVE'";
        if (homeroomTeacherId != null) {
            query +=
                    " AND EXISTS (SELECT 1 FROM homeroom_assignment h"
                            + " WHERE h.workspace_id = c.workspace_id AND h.class_id = c.id"
                            + " AND h.teacher_user_id = ? AND h.state IN ('PENDING', 'ACTIVE'))";
        }
        return page(
                "school_class",
                "c",
                query,
                (rs, _) -> mapClass(rs),
                ClassRow::id,
                workspaceId,
                cursor,
                limit,
                null,
                null,
                homeroomTeacherId == null
                        ? new Object[] {workspaceId}
                        : new Object[] {workspaceId, homeroomTeacherId});
    }

    public Page<RosterRow> pageRoster(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return page(
                "class_student",
                "cs",
                rosterSelect()
                        + " WHERE cs.workspace_id = ? AND cs.class_id = ?"
                        + " AND cs.state IN ('PENDING', 'ACTIVE')",
                (rs, _) -> mapRoster(rs),
                RosterRow::id,
                workspaceId,
                cursor,
                limit,
                "class_id",
                classId,
                workspaceId,
                classId);
    }

    public Page<CourseRow> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return page(
                "course",
                "c",
                "SELECT c.id, c.workspace_id, c.class_id, c.name, c.state, c.version"
                        + " FROM course c WHERE c.workspace_id = ? AND c.class_id = ?"
                        + " AND c.state = 'ACTIVE'",
                (rs, _) -> mapCourse(rs),
                CourseRow::id,
                workspaceId,
                cursor,
                limit,
                "class_id",
                classId,
                workspaceId,
                classId);
    }

    public Page<EnrollmentListRow> pageEnrollments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return page(
                "enrollment",
                "e",
                "SELECT e.id, e.class_student_id, e.course_subject_id, cs.student_user_id,"
                        + " e.state, e.version FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                        + " AND e.state IN ('PENDING', 'ACTIVE')",
                (rs, _) ->
                        new EnrollmentListRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("class_student_id", UUID.class),
                                rs.getObject("course_subject_id", UUID.class),
                                rs.getObject("student_user_id", UUID.class),
                                rs.getString("state"),
                                rs.getLong("version")),
                EnrollmentListRow::id,
                workspaceId,
                cursor,
                limit,
                "course_subject_id",
                courseSubjectId,
                workspaceId,
                courseSubjectId);
    }

    public Page<TeachingAssignmentRow> pageTeachingAssignments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return page(
                "teaching_assignment",
                "t",
                "SELECT t.id, t.workspace_id, t.course_subject_id, t.teacher_user_id, t.state, t.version"
                        + " FROM teaching_assignment t WHERE t.workspace_id = ?"
                        + " AND t.course_subject_id = ? AND t.state IN ('PENDING', 'ACTIVE')",
                (rs, _) -> mapTeachingAssignment(rs),
                TeachingAssignmentRow::id,
                workspaceId,
                cursor,
                limit,
                "course_subject_id",
                courseSubjectId,
                workspaceId,
                courseSubjectId);
    }

    public Page<HomeroomAssignmentRow> pageHomeroomAssignments(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return page(
                "homeroom_assignment",
                "h",
                "SELECT h.id, h.workspace_id, h.class_id, h.teacher_user_id, h.state, h.version"
                        + " FROM homeroom_assignment h WHERE h.workspace_id = ?"
                        + " AND h.class_id = ? AND h.state IN ('PENDING', 'ACTIVE')",
                (rs, _) -> mapHomeroomAssignment(rs),
                HomeroomAssignmentRow::id,
                workspaceId,
                cursor,
                limit,
                "class_id",
                classId,
                workspaceId,
                classId);
    }
}
