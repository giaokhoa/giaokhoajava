package com.giaokhoa.teaching.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Truy cập dữ liệu giảng dạy theo phạm vi: mọi câu truy vấn đều khóa theo {@code workspace_id} (và
 * khóa cha {@code class_id}/{@code course_id} khi áp dụng) nên không có đường đọc hay ghi nào vượt
 * scope của workspace đang thao tác. RLS ở tầng DB là chốt chặn cuối; repository này là lớp khóa
 * phạm vi chính, theo cùng cách các service hiện có lọc tường minh theo workspace thay vì trông vào
 * context của transaction.
 *
 * <p>Dòng "hiện hành" của roster, môn–khóa, ghi danh là dòng ở state PENDING hoặc ACTIVE; dòng
 * REVOKED là lịch sử, chỉ đọc khi cần đối chiếu, không nằm trong danh sách hiện hành. Thu hồi chỉ
 * là UPDATE state/revoked_at kèm tăng version — không có DELETE vật lý ở đây, khớp trigger giữ lịch
 * sử của các migration V014–V017.
 */
@Repository
public class TeachingRepository {
    private final JdbcTemplate jdbc;

    public TeachingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    /**
     * Gói còn hiệu lực theo đúng mẫu đã chốt ở route tạo lớp của US2: workspace chưa từng có kỳ gói
     * nào thì không bị chặn; đã có kỳ thì phải có ít nhất một kỳ đang trong hiệu lực.
     */
    public boolean subscriptionCurrent(UUID workspaceId) {
        Integer total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                        Integer.class,
                        workspaceId);
        if (total == null || total == 0) {
            return true;
        }
        Integer active =
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period WHERE workspace_id = ?"
                                + " AND valid_from <= now() AND expires_at > now()",
                        Integer.class,
                        workspaceId);
        return active != null && active > 0;
    }

    public boolean accountExists(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM user_account WHERE id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    public boolean hasActiveCenterMembership(UUID workspaceId, UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM center_membership"
                                + " WHERE workspace_id = ? AND user_id = ? AND state = 'ACTIVE'",
                        Integer.class,
                        workspaceId,
                        userId);
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

    private static String rosterSelect() {
        return "SELECT cs.id, cs.workspace_id, cs.class_id, cs.student_user_id, u.username,"
                + " cs.state, cs.version FROM class_student cs"
                + " JOIN user_account u ON u.id = cs.student_user_id";
    }

    private static ClassRow mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("name"),
                rs.getString("state"),
                rs.getLong("version"));
    }

    private static RosterRow mapRoster(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RosterRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("class_id", UUID.class),
                rs.getObject("student_user_id", UUID.class),
                rs.getString("username"),
                rs.getString("state"),
                rs.getLong("version"));
    }

    private static CourseRow mapCourse(java.sql.ResultSet rs) throws java.sql.SQLException {
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
}
