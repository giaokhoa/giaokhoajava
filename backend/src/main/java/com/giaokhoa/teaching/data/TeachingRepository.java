package com.giaokhoa.teaching.data;

import com.giaokhoa.subscriptions.SubscriptionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Truy cập dữ liệu giảng dạy theo phạm vi: mọi câu truy vấn đều khóa theo {@code workspace_id} (và
 * khóa cha {@code class_id}/{@code course_id} khi áp dụng) nên không có đường đọc hay ghi nào vượt
 * scope của workspace đang thao tác. Lọc tường minh ở đây và RLS ở tầng DB là hai lớp khóa phạm vi
 * cùng giữ: policy RLS khóa theo context workspace của transaction, nên service gọi repository này
 * phải đặt context qua {@code WorkspaceTransaction.applyContext} trước lời gọi đầu tiên trong
 * transaction (theo tiền lệ SystemPracticeService) — thiếu context thì runtime role không BYPASSRLS
 * không thấy và không ghi được hàng nào.
 *
 * <p>Các UPDATE theo id trần (đổi tên, thu hồi) chỉ an toàn khi lời gọi đã lấy lock có phạm vi
 * {@code FOR UPDATE} trên chính hàng đó trong cùng transaction trước đó (tiền lệ CenterService); ai
 * gọi thẳng repository này phải giữ đúng kỷ luật khóa-trước đó.
 *
 * <p>Dòng "hiện hành" của roster, môn–khóa, ghi danh và phân công là dòng ở state PENDING hoặc
 * ACTIVE; dòng REVOKED là lịch sử, chỉ đọc khi cần đối chiếu, không nằm trong danh sách hiện hành.
 * Thu hồi chỉ là UPDATE state/revoked_at kèm tăng version — không có DELETE vật lý ở đây, khớp
 * trigger giữ lịch sử của các migration V014–V017.
 */
@Repository
public class TeachingRepository {
    private final JdbcTemplate jdbc;
    private final TeachingPageRepository pages;

    public TeachingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.pages = new TeachingPageRepository(jdbc);
    }

    /** Thông tin tối thiểu của workspace để service kiểm tra tiền điều kiện dữ kiện. */
    public record WorkspaceInfo(UUID id, String kind, String state) {}

    /** Một lớp trong đúng một workspace. */
    public record ClassRow(UUID id, UUID workspaceId, String name, String state, long version) {}

    /** Một dòng roster hiện hành hoặc lịch sử, kèm username của học sinh để hiển thị tối thiểu. */
    public record RosterRow(
            UUID id,
            UUID workspaceId,
            UUID classId,
            UUID studentUserId,
            String username,
            String state,
            long version) {}

    /** Một khóa thuộc đúng một lớp của một workspace. */
    public record CourseRow(
            UUID id, UUID workspaceId, UUID classId, String name, String state, long version) {}

    /** Một môn gắn vào khóa: id là phạm vi riêng của cặp khóa–môn, subjectId trỏ danh mục. */
    public record CourseSubjectRow(
            UUID id, UUID courseId, UUID subjectId, String subjectName, String state) {}

    /** Một môn trong danh mục tham chiếu dùng chung. */
    public record SubjectRow(UUID id, String code, String name, String state) {}

    /** Một môn–khóa đầy đủ phạm vi: kèm lớp và khóa cha để service kiểm tra cùng-lớp/cùng-scope. */
    public record CourseSubjectScopeRow(
            UUID id, UUID workspaceId, UUID classId, UUID courseId, UUID subjectId, String state) {}

    /** Một ghi danh hiện hành hoặc lịch sử giữa một dòng roster và một môn–khóa. */
    public record EnrollmentRow(
            UUID id,
            UUID workspaceId,
            UUID classStudentId,
            UUID courseSubjectId,
            String state,
            long version) {}

    /** Một ghi danh trong danh sách của một môn–khóa, kèm học sinh của dòng roster của nó. */
    public record EnrollmentListRow(
            UUID id,
            UUID classStudentId,
            UUID courseSubjectId,
            UUID studentUserId,
            String state,
            long version) {}

    /** Một phân công giảng dạy hiện hành hoặc lịch sử của một giáo viên cho một môn–khóa. */
    public record TeachingAssignmentRow(
            UUID id,
            UUID workspaceId,
            UUID courseSubjectId,
            UUID teacherUserId,
            String state,
            long version) {}

    /** Một phân công chủ nhiệm hiện hành hoặc lịch sử của một giáo viên cho một lớp. */
    public record HomeroomAssignmentRow(
            UUID id,
            UUID workspaceId,
            UUID classId,
            UUID teacherUserId,
            String state,
            long version) {}

    public Optional<WorkspaceInfo> findWorkspace(UUID workspaceId) {
        return jdbc
                .query(
                        "SELECT id, kind, state FROM workspace WHERE id = ?",
                        (rs, _) ->
                                new WorkspaceInfo(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("kind"),
                                        rs.getString("state")),
                        workspaceId)
                .stream()
                .findFirst();
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

    public boolean hasCurrentHomeroomClass(UUID workspaceId, UUID teacherUserId) {
        return pages.hasCurrentHomeroomClass(workspaceId, teacherUserId);
    }

    public Page<ClassRow> pageClasses(
            UUID workspaceId,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable UUID homeroomTeacherId) {
        return pages.pageClasses(workspaceId, cursor, limit, homeroomTeacherId);
    }

    public Page<RosterRow> pageRoster(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return pages.pageRoster(workspaceId, classId, cursor, limit);
    }

    public Page<CourseRow> pageCourses(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return pages.pageCourses(workspaceId, classId, cursor, limit);
    }

    public Page<EnrollmentListRow> pageEnrollments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return pages.pageEnrollments(workspaceId, courseSubjectId, cursor, limit);
    }

    public Page<TeachingAssignmentRow> pageTeachingAssignments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return pages.pageTeachingAssignments(workspaceId, courseSubjectId, cursor, limit);
    }

    public Page<HomeroomAssignmentRow> pageHomeroomAssignments(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        return pages.pageHomeroomAssignments(workspaceId, classId, cursor, limit);
    }

    public boolean subscriptionCurrent(UUID workspaceId) {
        // Personal lấy gói làm entitlement bắt buộc: phải có kỳ đang hiệu lực mới mở rộng
        // được. Center giữ quy tắc của SubscriptionPolicy: chưa từng xác nhận gói thì chưa
        // bị khóa, đã có gói thì phải còn hiệu lực.
        boolean personal =
                !jdbc.queryForList(
                                "SELECT id FROM workspace WHERE id = ? AND kind = 'PERSONAL'",
                                UUID.class,
                                workspaceId)
                        .isEmpty();
        return personal
                ? SubscriptionPolicy.current(jdbc, workspaceId)
                : SubscriptionPolicy.currentOrUnconfirmed(jdbc, workspaceId);
    }

    public boolean accountExists(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM user_account WHERE id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    /** Membership ACTIVE của center kèm đúng một role tiền đề — tiền đề của nguồn phân công. */
    public boolean hasActiveCenterMembershipWithRole(UUID workspaceId, UUID userId, String role) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                + " AND m.state = 'ACTIVE' AND r.role = ?",
                        Integer.class,
                        workspaceId,
                        userId,
                        role);
        return count != null && count > 0;
    }

    // ---------- Lớp ----------

    public void insertClass(UUID id, UUID workspaceId, String name) {
        jdbc.update(
                "INSERT INTO school_class (id, workspace_id, name, state)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                name);
    }

    public Optional<ClassRow> findClass(UUID workspaceId, UUID classId) {
        return queryClass(" WHERE workspace_id = ? AND id = ?", workspaceId, classId);
    }

    /** Bản khóa của findClass cho đường mutation: giữ hàng tới hết transaction. */
    public Optional<ClassRow> lockClass(UUID workspaceId, UUID classId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, name, state, version FROM school_class"
                                + " WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        (rs, _) -> mapClass(rs),
                        workspaceId,
                        classId)
                .stream()
                .findFirst();
    }

    /** Các lớp hiện hành của workspace, cũ nhất trước; lớp đã thu hồi là lịch sử, không liệt kê. */
    public List<ClassRow> listClasses(UUID workspaceId) {
        return jdbc.query(
                "SELECT id, workspace_id, name, state, version FROM school_class"
                        + " WHERE workspace_id = ? AND state = 'ACTIVE'"
                        + " ORDER BY created_at ASC, id ASC",
                (rs, _) -> mapClass(rs),
                workspaceId);
    }

    public void renameClass(UUID classId, String name) {
        jdbc.update(
                "UPDATE school_class SET name = ?, version = version + 1, updated_at = now()"
                        + " WHERE id = ?",
                name,
                classId);
    }

    public void revokeClass(UUID classId) {
        jdbc.update(
                "UPDATE school_class SET state = 'REVOKED', version = version + 1,"
                        + " updated_at = now() WHERE id = ?",
                classId);
    }

    // ---------- Roster ----------

    public void insertRosterEntry(UUID id, UUID workspaceId, UUID classId, UUID studentUserId) {
        jdbc.update(
                "INSERT INTO class_student (id, workspace_id, class_id, student_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                classId,
                studentUserId);
    }

    public Optional<RosterRow> findCurrentRosterEntry(
            UUID workspaceId, UUID classId, UUID studentUserId) {
        return jdbc
                .query(
                        rosterSelect()
                                + " WHERE cs.workspace_id = ? AND cs.class_id = ?"
                                + " AND cs.student_user_id = ?"
                                + " AND cs.state IN ('PENDING', 'ACTIVE')",
                        (rs, _) -> mapRoster(rs),
                        workspaceId,
                        classId,
                        studentUserId)
                .stream()
                .findFirst();
    }

    public Optional<RosterRow> lockRosterEntry(UUID workspaceId, UUID classId, UUID rosterId) {
        return jdbc
                .query(
                        "SELECT cs.id, cs.workspace_id, cs.class_id, cs.student_user_id,"
                                + " u.username, cs.state, cs.version FROM class_student cs"
                                + " JOIN user_account u ON u.id = cs.student_user_id"
                                + " WHERE cs.workspace_id = ? AND cs.class_id = ? AND cs.id = ?"
                                + " FOR UPDATE OF cs",
                        (rs, _) -> mapRoster(rs),
                        workspaceId,
                        classId,
                        rosterId)
                .stream()
                .findFirst();
    }

    /** Roster hiện hành của một lớp, theo thứ tự vào lớp ổn định. */
    public List<RosterRow> listRoster(UUID workspaceId, UUID classId) {
        return jdbc.query(
                rosterSelect()
                        + " WHERE cs.workspace_id = ? AND cs.class_id = ?"
                        + " AND cs.state IN ('PENDING', 'ACTIVE')"
                        + " ORDER BY cs.created_at ASC, cs.id ASC",
                (rs, _) -> mapRoster(rs),
                workspaceId,
                classId);
    }

    public void revokeRosterEntry(UUID rosterId) {
        jdbc.update(
                "UPDATE class_student SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                rosterId);
    }

    /**
     * Thu hồi mọi ghi danh hiện hành phụ thuộc một dòng roster, giữ nguyên hàng lịch sử. Đây là
     * phần dữ kiện nguồn của bất biến "thu hồi roster thì thu hồi ghi danh phụ thuộc"; phần gỡ
     * nguồn policy của ghi danh thuộc lớp projection riêng.
     */
    public void revokeEnrollmentsOfRosterEntry(UUID workspaceId, UUID classStudentId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND class_student_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                classStudentId);
    }

    // ---------- Khóa và môn của khóa ----------

    public void insertCourse(UUID id, UUID workspaceId, UUID classId, String name) {
        jdbc.update(
                "INSERT INTO course (id, workspace_id, class_id, name, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                classId,
                name);
    }

    public Optional<CourseRow> findCourse(UUID workspaceId, UUID courseId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_id, name, state, version FROM course"
                                + " WHERE workspace_id = ? AND id = ?",
                        (rs, _) -> mapCourse(rs),
                        workspaceId,
                        courseId)
                .stream()
                .findFirst();
    }

    /** Bản khóa của findCourse cho đường mutation: giữ hàng tới hết transaction. */
    public Optional<CourseRow> lockCourse(UUID workspaceId, UUID courseId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_id, name, state, version FROM course"
                                + " WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        (rs, _) -> mapCourse(rs),
                        workspaceId,
                        courseId)
                .stream()
                .findFirst();
    }

    /** Các khóa hiện hành của một lớp, cũ nhất trước. */
    public List<CourseRow> listCourses(UUID workspaceId, UUID classId) {
        return jdbc.query(
                "SELECT id, workspace_id, class_id, name, state, version FROM course"
                        + " WHERE workspace_id = ? AND class_id = ? AND state = 'ACTIVE'"
                        + " ORDER BY created_at ASC, id ASC",
                (rs, _) -> mapCourse(rs),
                workspaceId,
                classId);
    }

    public void renameCourse(UUID courseId, String name) {
        jdbc.update(
                "UPDATE course SET name = ?, version = version + 1, updated_at = now()"
                        + " WHERE id = ?",
                name,
                courseId);
    }

    public void revokeCourse(UUID courseId) {
        jdbc.update(
                "UPDATE course SET state = 'REVOKED', version = version + 1, updated_at = now()"
                        + " WHERE id = ?",
                courseId);
    }

    public void insertCourseSubject(
            UUID id, UUID workspaceId, UUID classId, UUID courseId, UUID subjectId) {
        jdbc.update(
                "INSERT INTO course_subject"
                        + " (id, workspace_id, class_id, course_id, subject_id, state)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                classId,
                courseId,
                subjectId);
    }

    /** Các môn hiện hành của một khóa, theo mã danh mục để thứ tự ổn định. */
    public List<CourseSubjectRow> listCurrentCourseSubjects(UUID workspaceId, UUID courseId) {
        return jdbc.query(
                "SELECT cs.id, cs.course_id, cs.subject_id, s.name AS subject_name, cs.state"
                        + " FROM course_subject cs JOIN subject s ON s.id = cs.subject_id"
                        + " WHERE cs.workspace_id = ? AND cs.course_id = ?"
                        + " AND cs.state IN ('PENDING', 'ACTIVE')"
                        + " ORDER BY s.code ASC, cs.id ASC",
                (rs, _) -> mapCourseSubject(rs),
                workspaceId,
                courseId);
    }

    /** Nạp môn của tối đa một trang khóa bằng một SQL thay vì một query cho mỗi khóa. */
    public Map<UUID, List<CourseSubjectRow>> currentSubjectsByCourse(
            UUID workspaceId, List<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        List<CourseSubjectRow> subjects =
                new NamedParameterJdbcTemplate(jdbc)
                        .query(
                                "SELECT cs.id, cs.course_id, cs.subject_id,"
                                        + " s.name AS subject_name, cs.state"
                                        + " FROM course_subject cs"
                                        + " JOIN subject s ON s.id = cs.subject_id"
                                        + " WHERE cs.workspace_id = :workspaceId"
                                        + " AND cs.course_id IN (:courseIds)"
                                        + " AND cs.state IN ('PENDING', 'ACTIVE')"
                                        + " ORDER BY cs.course_id, s.code, cs.id",
                                new MapSqlParameterSource()
                                        .addValue("workspaceId", workspaceId)
                                        .addValue("courseIds", courseIds),
                                (rs, _) -> mapCourseSubject(rs));
        Map<UUID, List<CourseSubjectRow>> byCourse = new HashMap<>();
        for (CourseSubjectRow subject : subjects) {
            byCourse.computeIfAbsent(subject.courseId(), _ -> new ArrayList<>()).add(subject);
        }
        return byCourse;
    }

    public Optional<CourseSubjectRow> findCurrentCourseSubject(
            UUID workspaceId, UUID courseId, UUID subjectId) {
        return jdbc
                .query(
                        "SELECT cs.id, cs.course_id, cs.subject_id, s.name AS subject_name,"
                                + " cs.state FROM course_subject cs"
                                + " JOIN subject s ON s.id = cs.subject_id"
                                + " WHERE cs.workspace_id = ? AND cs.course_id = ?"
                                + " AND cs.subject_id = ?"
                                + " AND cs.state IN ('PENDING', 'ACTIVE')",
                        (rs, _) -> mapCourseSubject(rs),
                        workspaceId,
                        courseId,
                        subjectId)
                .stream()
                .findFirst();
    }

    public Optional<CourseSubjectRow> lockCourseSubject(
            UUID workspaceId, UUID courseId, UUID courseSubjectId) {
        return jdbc
                .query(
                        "SELECT cs.id, cs.course_id, cs.subject_id, s.name AS subject_name,"
                                + " cs.state FROM course_subject cs"
                                + " JOIN subject s ON s.id = cs.subject_id"
                                + " WHERE cs.workspace_id = ? AND cs.course_id = ? AND cs.id = ?"
                                + " FOR UPDATE OF cs",
                        (rs, _) -> mapCourseSubject(rs),
                        workspaceId,
                        courseId,
                        courseSubjectId)
                .stream()
                .findFirst();
    }

    public void revokeCourseSubject(UUID courseSubjectId) {
        jdbc.update(
                "UPDATE course_subject SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                courseSubjectId);
    }

    /** Một dòng roster theo id trong đúng workspace, bất kể trạng thái. */
    public Optional<RosterRow> findRosterEntryById(UUID workspaceId, UUID rosterId) {
        return jdbc
                .query(
                        rosterSelect() + " WHERE cs.workspace_id = ? AND cs.id = ?",
                        (rs, _) -> mapRoster(rs),
                        workspaceId,
                        rosterId)
                .stream()
                .findFirst();
    }

    /** Một môn–khóa đầy đủ phạm vi (lớp/khóa cha, trạng thái của chính dòng) theo id. */
    public Optional<CourseSubjectScopeRow> findCourseSubjectScope(
            UUID workspaceId, UUID courseSubjectId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_id, course_id, subject_id, state"
                                + " FROM course_subject WHERE workspace_id = ? AND id = ?",
                        (rs, _) ->
                                new CourseSubjectScopeRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("workspace_id", UUID.class),
                                        rs.getObject("class_id", UUID.class),
                                        rs.getObject("course_id", UUID.class),
                                        rs.getObject("subject_id", UUID.class),
                                        rs.getString("state")),
                        workspaceId,
                        courseSubjectId)
                .stream()
                .findFirst();
    }

    // ---------- Ghi danh ----------

    public void insertEnrollment(
            UUID id, UUID workspaceId, UUID classStudentId, UUID courseSubjectId) {
        jdbc.update(
                "INSERT INTO enrollment"
                        + " (id, workspace_id, class_student_id, course_subject_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                classStudentId,
                courseSubjectId);
    }

    public Optional<EnrollmentRow> findCurrentEnrollment(
            UUID workspaceId, UUID classStudentId, UUID courseSubjectId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_student_id, course_subject_id, state,"
                                + " version FROM enrollment"
                                + " WHERE workspace_id = ? AND class_student_id = ?"
                                + " AND course_subject_id = ?"
                                + " AND state IN ('PENDING', 'ACTIVE')",
                        (rs, _) -> mapEnrollment(rs),
                        workspaceId,
                        classStudentId,
                        courseSubjectId)
                .stream()
                .findFirst();
    }

    public Optional<EnrollmentRow> lockEnrollment(UUID workspaceId, UUID enrollmentId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_student_id, course_subject_id, state,"
                                + " version FROM enrollment"
                                + " WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        (rs, _) -> mapEnrollment(rs),
                        workspaceId,
                        enrollmentId)
                .stream()
                .findFirst();
    }

    /** Các ghi danh hiện hành của một môn–khóa kèm học sinh của dòng roster, thứ tự tạo ổn định. */
    public List<EnrollmentListRow> listCurrentEnrollments(UUID workspaceId, UUID courseSubjectId) {
        return jdbc.query(
                "SELECT e.id, e.class_student_id, e.course_subject_id, cs.student_user_id,"
                        + " e.state, e.version FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                        + " AND e.state IN ('PENDING', 'ACTIVE')"
                        + " ORDER BY e.created_at ASC, e.id ASC",
                (rs, _) ->
                        new EnrollmentListRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("class_student_id", UUID.class),
                                rs.getObject("course_subject_id", UUID.class),
                                rs.getObject("student_user_id", UUID.class),
                                rs.getString("state"),
                                rs.getLong("version")),
                workspaceId,
                courseSubjectId);
    }

    public void revokeEnrollment(UUID enrollmentId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                enrollmentId);
    }

    // ---------- Phân công giảng dạy và chủ nhiệm ----------

    public void insertTeachingAssignment(
            UUID id, UUID workspaceId, UUID courseSubjectId, UUID teacherUserId) {
        jdbc.update(
                "INSERT INTO teaching_assignment"
                        + " (id, workspace_id, course_subject_id, teacher_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                courseSubjectId,
                teacherUserId);
    }

    public Optional<TeachingAssignmentRow> findCurrentTeachingAssignment(
            UUID workspaceId, UUID courseSubjectId, UUID teacherUserId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, course_subject_id, teacher_user_id, state,"
                                + " version FROM teaching_assignment"
                                + " WHERE workspace_id = ? AND course_subject_id = ?"
                                + " AND teacher_user_id = ?"
                                + " AND state IN ('PENDING', 'ACTIVE')",
                        (rs, _) -> mapTeachingAssignment(rs),
                        workspaceId,
                        courseSubjectId,
                        teacherUserId)
                .stream()
                .findFirst();
    }

    public Optional<TeachingAssignmentRow> lockTeachingAssignment(
            UUID workspaceId, UUID courseSubjectId, UUID assignmentId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, course_subject_id, teacher_user_id, state,"
                                + " version FROM teaching_assignment"
                                + " WHERE workspace_id = ? AND course_subject_id = ? AND id = ?"
                                + " FOR UPDATE",
                        (rs, _) -> mapTeachingAssignment(rs),
                        workspaceId,
                        courseSubjectId,
                        assignmentId)
                .stream()
                .findFirst();
    }

    /** Các phân công giảng dạy hiện hành của một môn–khóa, theo thứ tự tạo ổn định. */
    public List<TeachingAssignmentRow> listCurrentTeachingAssignments(
            UUID workspaceId, UUID courseSubjectId) {
        return jdbc.query(
                "SELECT id, workspace_id, course_subject_id, teacher_user_id, state, version"
                        + " FROM teaching_assignment WHERE workspace_id = ?"
                        + " AND course_subject_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " ORDER BY created_at ASC, id ASC",
                (rs, _) -> mapTeachingAssignment(rs),
                workspaceId,
                courseSubjectId);
    }

    public void revokeTeachingAssignment(UUID assignmentId) {
        jdbc.update(
                "UPDATE teaching_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                assignmentId);
    }

    public void insertHomeroomAssignment(
            UUID id, UUID workspaceId, UUID classId, UUID teacherUserId) {
        jdbc.update(
                "INSERT INTO homeroom_assignment"
                        + " (id, workspace_id, class_id, teacher_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                id,
                workspaceId,
                classId,
                teacherUserId);
    }

    public Optional<HomeroomAssignmentRow> findCurrentHomeroomAssignment(
            UUID workspaceId, UUID classId, UUID teacherUserId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_id, teacher_user_id, state, version"
                                + " FROM homeroom_assignment"
                                + " WHERE workspace_id = ? AND class_id = ?"
                                + " AND teacher_user_id = ?"
                                + " AND state IN ('PENDING', 'ACTIVE')",
                        (rs, _) -> mapHomeroomAssignment(rs),
                        workspaceId,
                        classId,
                        teacherUserId)
                .stream()
                .findFirst();
    }

    public Optional<HomeroomAssignmentRow> lockHomeroomAssignment(
            UUID workspaceId, UUID classId, UUID assignmentId) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, class_id, teacher_user_id, state, version"
                                + " FROM homeroom_assignment"
                                + " WHERE workspace_id = ? AND class_id = ? AND id = ?"
                                + " FOR UPDATE",
                        (rs, _) -> mapHomeroomAssignment(rs),
                        workspaceId,
                        classId,
                        assignmentId)
                .stream()
                .findFirst();
    }

    /** Các phân công chủ nhiệm hiện hành của một lớp, theo thứ tự tạo ổn định. */
    public List<HomeroomAssignmentRow> listCurrentHomeroomAssignments(
            UUID workspaceId, UUID classId) {
        return jdbc.query(
                "SELECT id, workspace_id, class_id, teacher_user_id, state, version"
                        + " FROM homeroom_assignment WHERE workspace_id = ? AND class_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')"
                        + " ORDER BY created_at ASC, id ASC",
                (rs, _) -> mapHomeroomAssignment(rs),
                workspaceId,
                classId);
    }

    public void revokeHomeroomAssignment(UUID assignmentId) {
        jdbc.update(
                "UPDATE homeroom_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                assignmentId);
    }

    /** Các lớp mà một giáo viên đang có phân công chủ nhiệm hiện hành trong workspace. */
    public List<UUID> currentHomeroomClassIdsOfUser(UUID workspaceId, UUID teacherUserId) {
        return jdbc.queryForList(
                "SELECT class_id FROM homeroom_assignment WHERE workspace_id = ?"
                        + " AND teacher_user_id = ? AND state IN ('PENDING', 'ACTIVE')",
                UUID.class,
                workspaceId,
                teacherUserId);
    }

    /** Các môn–khóa mà một giáo viên đang có phân công giảng dạy hiện hành trong workspace. */
    public List<UUID> currentTeachingCourseSubjectIdsOfUser(UUID workspaceId, UUID teacherUserId) {
        return jdbc.queryForList(
                "SELECT course_subject_id FROM teaching_assignment WHERE workspace_id = ?"
                        + " AND teacher_user_id = ? AND state IN ('PENDING', 'ACTIVE')",
                UUID.class,
                workspaceId,
                teacherUserId);
    }

    /** Các môn–khóa mà một học sinh đang có ghi danh hiện hành trong workspace. */
    public List<UUID> currentEnrolledCourseSubjectIdsOfStudent(
            UUID workspaceId, UUID studentUserId) {
        return jdbc.queryForList(
                "SELECT e.course_subject_id FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " WHERE e.workspace_id = ? AND cs.student_user_id = ?"
                        + " AND e.state IN ('PENDING', 'ACTIVE')",
                UUID.class,
                workspaceId,
                studentUserId);
    }

    /** Mọi môn–khóa thuộc một lớp trong workspace. */
    public List<UUID> courseSubjectIdsOfClass(UUID workspaceId, UUID classId) {
        return jdbc.queryForList(
                "SELECT id FROM course_subject WHERE workspace_id = ? AND class_id = ?",
                UUID.class,
                workspaceId,
                classId);
    }

    /** Mọi khóa thuộc một lớp trong workspace. */
    public List<UUID> courseIdsOfClass(UUID workspaceId, UUID classId) {
        return jdbc.queryForList(
                "SELECT id FROM course WHERE workspace_id = ? AND class_id = ? ORDER BY id",
                UUID.class,
                workspaceId,
                classId);
    }

    /**
     * Các môn–khóa mà một dòng roster đang có ghi danh hiện hành — phạm vi bundle bị ảnh hưởng khi
     * thu hồi dòng roster đó.
     */
    public List<UUID> courseSubjectIdsOfRosterEntry(UUID workspaceId, UUID classStudentId) {
        return jdbc.queryForList(
                "SELECT course_subject_id FROM enrollment WHERE workspace_id = ?"
                        + " AND class_student_id = ? AND state IN ('PENDING', 'ACTIVE')",
                UUID.class,
                workspaceId,
                classStudentId);
    }

    /** Mọi môn–khóa thuộc một khóa trong workspace. */
    public List<UUID> courseSubjectIdsOfCourse(UUID workspaceId, UUID courseId) {
        return jdbc.queryForList(
                "SELECT id FROM course_subject WHERE workspace_id = ? AND course_id = ?",
                UUID.class,
                workspaceId,
                courseId);
    }

    // ---------- Đóng nguồn phụ thuộc (dùng bởi TeachingRevocationContributor) ----------

    /** Thu hồi mọi ghi danh hiện hành của một học sinh trong center, qua các dòng roster của họ. */
    public void revokeEnrollmentsOfStudent(UUID workspaceId, UUID studentUserId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " AND class_student_id IN (SELECT id FROM class_student"
                        + " WHERE workspace_id = ? AND student_user_id = ?)",
                workspaceId,
                workspaceId,
                studentUserId);
    }

    /** Thu hồi mọi dòng roster hiện hành của một người trong workspace. */
    public void revokeRosterEntriesOfUser(UUID workspaceId, UUID userId) {
        jdbc.update(
                "UPDATE class_student SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND student_user_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                userId);
    }

    /** Thu hồi mọi phân công giảng dạy hiện hành của một giáo viên trong workspace. */
    public void revokeTeachingAssignmentsOfUser(UUID workspaceId, UUID teacherUserId) {
        jdbc.update(
                "UPDATE teaching_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND teacher_user_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                teacherUserId);
    }

    /** Thu hồi mọi phân công chủ nhiệm hiện hành của một giáo viên trong workspace. */
    public void revokeHomeroomAssignmentsOfUser(UUID workspaceId, UUID teacherUserId) {
        jdbc.update(
                "UPDATE homeroom_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND teacher_user_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                teacherUserId);
    }

    /** Thu hồi mọi ghi danh hiện hành thuộc các môn–khóa của một lớp. */
    public void revokeEnrollmentsOfClass(UUID workspaceId, UUID classId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " AND course_subject_id IN (SELECT id FROM course_subject"
                        + " WHERE workspace_id = ? AND class_id = ?)",
                workspaceId,
                workspaceId,
                classId);
    }

    /** Thu hồi mọi phân công giảng dạy hiện hành thuộc các môn–khóa của một lớp. */
    public void revokeTeachingAssignmentsOfClass(UUID workspaceId, UUID classId) {
        jdbc.update(
                "UPDATE teaching_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " AND course_subject_id IN (SELECT id FROM course_subject"
                        + " WHERE workspace_id = ? AND class_id = ?)",
                workspaceId,
                workspaceId,
                classId);
    }

    /** Thu hồi mọi phân công chủ nhiệm hiện hành của một lớp. */
    public void revokeHomeroomAssignmentsOfClass(UUID workspaceId, UUID classId) {
        jdbc.update(
                "UPDATE homeroom_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND class_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                classId);
    }

    /** Thu hồi mọi ghi danh hiện hành thuộc các môn–khóa của một khóa. */
    public void revokeEnrollmentsOfCourse(UUID workspaceId, UUID courseId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " AND course_subject_id IN (SELECT id FROM course_subject"
                        + " WHERE workspace_id = ? AND course_id = ?)",
                workspaceId,
                workspaceId,
                courseId);
    }

    /** Thu hồi mọi phân công giảng dạy hiện hành thuộc các môn–khóa của một khóa. */
    public void revokeTeachingAssignmentsOfCourse(UUID workspaceId, UUID courseId) {
        jdbc.update(
                "UPDATE teaching_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND state IN ('PENDING', 'ACTIVE')"
                        + " AND course_subject_id IN (SELECT id FROM course_subject"
                        + " WHERE workspace_id = ? AND course_id = ?)",
                workspaceId,
                workspaceId,
                courseId);
    }

    /** Thu hồi mọi ghi danh hiện hành của một môn–khóa. */
    public void revokeEnrollmentsOfCourseSubject(UUID workspaceId, UUID courseSubjectId) {
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND course_subject_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                courseSubjectId);
    }

    /** Thu hồi mọi phân công giảng dạy hiện hành của một môn–khóa. */
    public void revokeTeachingAssignmentsOfCourseSubject(UUID workspaceId, UUID courseSubjectId) {
        jdbc.update(
                "UPDATE teaching_assignment SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND course_subject_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')",
                workspaceId,
                courseSubjectId);
    }

    // ---------- Danh mục môn ----------

    /** Toàn bộ môn đang ACTIVE của danh mục tham chiếu, theo mã. */
    public List<SubjectRow> listSubjects() {
        return jdbc.query(
                "SELECT id, code, name, state FROM subject WHERE state = 'ACTIVE'"
                        + " ORDER BY code ASC",
                (rs, _) ->
                        new SubjectRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("state")));
    }

    public Optional<SubjectRow> findSubject(UUID subjectId) {
        return jdbc
                .query(
                        "SELECT id, code, name, state FROM subject WHERE id = ?",
                        (rs, _) ->
                                new SubjectRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        rs.getString("state")),
                        subjectId)
                .stream()
                .findFirst();
    }

    // ---------- Ánh xạ dòng ----------

    private Optional<ClassRow> queryClass(String where, Object... args) {
        return jdbc
                .query(
                        "SELECT id, workspace_id, name, state, version FROM school_class" + where,
                        (rs, _) -> mapClass(rs),
                        args)
                .stream()
                .findFirst();
    }

    static String rosterSelect() {
        return "SELECT cs.id, cs.workspace_id, cs.class_id, cs.student_user_id, u.username,"
                + " cs.state, cs.version FROM class_student cs"
                + " JOIN user_account u ON u.id = cs.student_user_id";
    }

    static ClassRow mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("name"),
                rs.getString("state"),
                rs.getLong("version"));
    }

    static RosterRow mapRoster(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RosterRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("class_id", UUID.class),
                rs.getObject("student_user_id", UUID.class),
                rs.getString("username"),
                rs.getString("state"),
                rs.getLong("version"));
    }

    static CourseRow mapCourse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CourseRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("class_id", UUID.class),
                rs.getString("name"),
                rs.getString("state"),
                rs.getLong("version"));
    }

    private static CourseSubjectRow mapCourseSubject(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new CourseSubjectRow(
                rs.getObject("id", UUID.class),
                rs.getObject("course_id", UUID.class),
                rs.getObject("subject_id", UUID.class),
                rs.getString("subject_name"),
                rs.getString("state"));
    }

    private static EnrollmentRow mapEnrollment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EnrollmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("class_student_id", UUID.class),
                rs.getObject("course_subject_id", UUID.class),
                rs.getString("state"),
                rs.getLong("version"));
    }

    static TeachingAssignmentRow mapTeachingAssignment(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new TeachingAssignmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("course_subject_id", UUID.class),
                rs.getObject("teacher_user_id", UUID.class),
                rs.getString("state"),
                rs.getLong("version"));
    }

    static HomeroomAssignmentRow mapHomeroomAssignment(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new HomeroomAssignmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("class_id", UUID.class),
                rs.getObject("teacher_user_id", UUID.class),
                rs.getString("state"),
                rs.getLong("version"));
    }
}
