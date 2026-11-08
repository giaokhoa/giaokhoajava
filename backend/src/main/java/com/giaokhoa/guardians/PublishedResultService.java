package com.giaokhoa.guardians;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Phía đọc kết quả đã công bố của một học sinh trong một workspace, phục vụ route {@code GET
 * /workspaces/{wid}/students/{sid}/results} của tầng HTTP. Đường đọc theo đúng giao thức protected
 * request: resolve metadata tối thiểu (chỉ id của các kết quả {@code PUBLISHED} của đúng học sinh
 * trong đúng workspace — kết quả DRAFT không bao giờ thành ứng viên, kể cả khi DB cho phép nhiều
 * kết quả trên một bài nộp), hỏi PDP mới về {@code read.published} cho TỪNG kết quả bằng token của
 * chính actor qua {@link ProtectedActionExecutor}, rồi chỉ đọc DTO của các hàng được phép. Danh
 * sách không dùng một permission trên collection để trả mọi hàng, và không lộ tổng số hàng tồn tại.
 *
 * <p>Khi actor không phải chính học sinh và không hàng nào được PDP cho phép, lời gọi bị từ chối
 * 403 thay vì trả danh sách rỗng: với phụ huynh chưa được duyệt, liên kết đã gỡ, con khác hoặc
 * center khác, "không có kết quả nào" và "không có quyền" không được phép trông giống nhau. Chính
 * học sinh đọc kết quả của mình thì được danh sách rỗng khi chưa có kết quả nào công bố.
 */
@Component
public class PublishedResultService {

    private static final String READ_PUBLISHED =
            PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName();

    private final ProtectedActionExecutor executor;
    private final WorkspaceTransaction workspaces;
    private final JdbcTemplate jdbc;

    public PublishedResultService(
            ProtectedActionExecutor executor, WorkspaceTransaction workspaces, JdbcTemplate jdbc) {
        this.executor = executor;
        this.workspaces = workspaces;
        this.jdbc = jdbc;
    }

    /** Các kết quả đã công bố của học sinh mà actor hiện tại được phép đọc, mới công bố trước. */
    public List<PublishedResultView> listPublishedResults(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            UUID studentUserId) {
        UUID actorUserId = executor.actorUserId(authentication);
        List<UUID> candidates =
                Objects.requireNonNull(
                        workspaces.inWorkspace(
                                workspaceId,
                                () ->
                                        jdbc.queryForList(
                                                "SELECT id FROM sample_result"
                                                        + " WHERE workspace_id = ?"
                                                        + " AND student_user_id = ?"
                                                        + " AND state = 'PUBLISHED'",
                                                UUID.class,
                                                workspaceId,
                                                studentUserId)),
                        "candidates");
        List<UUID> allowedIds = List.of();
        if (!candidates.isEmpty()) {
            List<ProtectedActionExecutor.Target> targets =
                    candidates.stream()
                            .map(resultId ->
                                    new ProtectedActionExecutor.Target(
                                            NativePolicyTemplates.resultKey(workspaceId, resultId),
                                            List.of(READ_PUBLISHED)))
                            .toList();
            ProtectedActionExecutor.Decisions decisions =
                    executor.evaluate(authentication, request, workspaceId, targets);
            allowedIds =
                    candidates.stream()
                            .filter(resultId ->
                                    decisions.allowed(
                                            NativePolicyTemplates.resultKey(workspaceId, resultId),
                                            READ_PUBLISHED))
                            .toList();
        }
        if (allowedIds.isEmpty()) {
            if (actorUserId.equals(studentUserId)) {
                return List.of();
            }
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Không có quyền xem kết quả đã công bố của học sinh này.");
        }
        List<UUID> ids = allowedIds;
        return Objects.requireNonNull(
                workspaces.inWorkspace(workspaceId, () -> readViews(workspaceId, ids)), "views");
    }

    private List<PublishedResultView> readViews(UUID workspaceId, List<UUID> resultIds) {
        String placeholders = String.join(", ", Collections.nCopies(resultIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(resultIds);
        return jdbc.query(
                "SELECT r.id, r.student_user_id, u.username, sc.name AS class_name,"
                        + " c.name AS course_name, s.name AS subject_name, r.score_stub,"
                        + " r.comment_stub, r.published_at"
                        + " FROM sample_result r"
                        + " JOIN course_subject cs ON cs.id = r.course_subject_id"
                        + " AND cs.workspace_id = r.workspace_id"
                        + " JOIN course c ON c.id = cs.course_id AND c.workspace_id = r.workspace_id"
                        + " JOIN school_class sc ON sc.id = c.class_id"
                        + " AND sc.workspace_id = r.workspace_id"
                        + " JOIN subject s ON s.id = cs.subject_id"
                        + " JOIN user_account u ON u.id = r.student_user_id"
                        + " WHERE r.workspace_id = ? AND r.id IN ("
                        + placeholders
                        + ")"
                        + " ORDER BY r.published_at DESC, r.id",
                (rs, _) -> {
                    OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
                    return new PublishedResultView(
                            rs.getObject("id", UUID.class),
                            new PublishedResultView.Student(
                                    rs.getObject("student_user_id", UUID.class),
                                    rs.getString("username")),
                            rs.getString("class_name"),
                            rs.getString("course_name"),
                            rs.getString("subject_name"),
                            rs.getString("score_stub"),
                            rs.getString("comment_stub"),
                            publishedAt == null ? null : publishedAt.toInstant());
                },
                args.toArray());
    }
}
