package com.giaokhoa.guardians;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

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
@Service
public class PublishedResultService {

    private static final String READ_PUBLISHED =
            PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName();

    private final ProtectedActionExecutor executor;
    private final WorkspaceTransaction workspaces;
    private final JdbcTemplate jdbc;
    private static final SecureRandom CURSOR_RANDOM = new SecureRandom();
    private static final int SCAN_BATCH = 100;
    private static final int MAX_CURSOR_LENGTH = 256;
    private static final int CURSOR_PART_COUNT = 2;
    private final byte[] cursorKey = new byte[32];

    public record ResultPage(List<PublishedResultView> items, @Nullable String nextCursor) {
        public ResultPage {
            items = List.copyOf(items);
        }
    }

    private record Candidate(UUID id, OffsetDateTime publishedAt) {}

    public PublishedResultService(
            ProtectedActionExecutor executor, WorkspaceTransaction workspaces, JdbcTemplate jdbc) {
        this.executor = executor;
        this.workspaces = workspaces;
        this.jdbc = jdbc;
        CURSOR_RANDOM.nextBytes(cursorKey);
    }

    /** Giữ API service cũ cho các caller nội bộ; HTTP sử dụng bản phân trang. */
    public List<PublishedResultView> listPublishedResults(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            UUID studentUserId) {
        return pagePublishedResults(authentication, request, workspaceId, studentUserId, null, 100)
                .items();
    }

    /**
     * Keyset theo (published_at, id). Mỗi batch tối đa 100 ID; PDP kiểm tra từng kết quả, không đọc
     * DTO khi mới chỉ có quyết định ngoài transaction. Trước khi dựng trang cuối, kiểm tra lại mọi
     * ID được chọn và đọc DTO trong cùng fence chốt để chặn revoke đua nhau.
     */
    public ResultPage pagePublishedResults(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            UUID studentUserId,
            @Nullable String cursor,
            int requestedLimit) {
        UUID actorUserId = executor.actorUserId(authentication);
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        @Nullable UUID anchorId =
                decodeCursor(cursor, actorUserId, workspaceId, studentUserId, limit);
        @Nullable Candidate anchor = null;
        if (anchorId != null) {
            UUID id = anchorId;
            anchor =
                    workspaces.inWorkspace(
                            workspaceId,
                            () -> {
                                List<Candidate> anchors =
                                        jdbc.query(
                                                "SELECT id, published_at FROM sample_result"
                                                        + " WHERE workspace_id = ? AND student_user_id = ?"
                                                        + " AND state = 'PUBLISHED' AND id = ?",
                                                (rs, _) ->
                                                        new Candidate(
                                                                rs.getObject("id", UUID.class),
                                                                rs.getObject(
                                                                        "published_at",
                                                                        OffsetDateTime.class)),
                                                workspaceId,
                                                studentUserId,
                                                id);
                                return anchors.isEmpty() ? null : anchors.getFirst();
                            });
            if (anchor == null) {
                throw invalidCursor();
            }
        }

        List<Candidate> admitted = new ArrayList<>(limit + 1);
        while (admitted.size() <= limit) {
            Candidate from = anchor;
            List<Candidate> candidates =
                    Objects.requireNonNull(
                            workspaces.inWorkspace(
                                    workspaceId,
                                    () -> {
                                        String sql =
                                                "SELECT id, published_at FROM sample_result"
                                                        + " WHERE workspace_id = ? AND student_user_id = ?"
                                                        + " AND state = 'PUBLISHED'"
                                                        + (from == null
                                                                ? ""
                                                                : " AND (published_at, id) < (?, ?)")
                                                        + " ORDER BY published_at DESC, id DESC LIMIT ?";
                                        Object[] params =
                                                from == null
                                                        ? new Object[] {
                                                            workspaceId, studentUserId, SCAN_BATCH
                                                        }
                                                        : new Object[] {
                                                            workspaceId,
                                                            studentUserId,
                                                            from.publishedAt(),
                                                            from.id(),
                                                            SCAN_BATCH
                                                        };
                                        return jdbc.query(
                                                sql,
                                                (rs, _) ->
                                                        new Candidate(
                                                                rs.getObject("id", UUID.class),
                                                                rs.getObject(
                                                                        "published_at",
                                                                        OffsetDateTime.class)),
                                                params);
                                    }),
                            "candidates");
            if (candidates.isEmpty()) {
                break;
            }
            List<ProtectedActionExecutor.Target> targets = targets(workspaceId, candidates);
            ProtectedActionExecutor.Decisions decisions =
                    executor.evaluate(authentication, request, workspaceId, targets);
            for (Candidate candidate : candidates) {
                if (decisions.allowed(
                        NativePolicyTemplates.resultKey(workspaceId, candidate.id()),
                        READ_PUBLISHED)) {
                    admitted.add(candidate);
                    if (admitted.size() > limit) {
                        break;
                    }
                }
            }
            anchor = candidates.getLast();
            if (candidates.size() < SCAN_BATCH || admitted.size() > limit) {
                break;
            }
        }

        // Kể cả không có PUBLISHED row vẫn phải đồng bộ eligibility + ACCOUNT/WORKSPACE fence.
        // Không được trả 200 [] khi tài khoản hoặc workspace đang PENDING/FAILED chưa xác minh.
        // Nhánh rỗng phải đi qua fence của đúng workspace đang đọc (không dùng executeForActor
        // với workspace null, vì manifest khi đó thiếu partition workspace).
        if (admitted.isEmpty()) {
            return executor.execute(
                    authentication,
                    request,
                    workspaceId,
                    List.of(
                            new ProtectedActionExecutor.Target(
                                    NativePolicyTemplates.profileKey(actorUserId),
                                    List.of(
                                            PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                    .scopeName()))),
                    () -> new ResultPage(emptyOrDenied(actorUserId, studentUserId), null));
        }
        List<Candidate> candidatePage = List.copyOf(admitted);
        return executor.evaluateAndRead(
                authentication,
                request,
                workspaceId,
                targets(workspaceId, candidatePage),
                confirmed -> {
                    List<Candidate> allowed =
                            candidatePage.stream()
                                    .filter(candidate ->
                                            confirmed.allowed(
                                                    NativePolicyTemplates.resultKey(
                                                            workspaceId, candidate.id()),
                                                    READ_PUBLISHED))
                                    .toList();
                    if (allowed.isEmpty()) {
                        return new ResultPage(emptyOrDenied(actorUserId, studentUserId), null);
                    }
                    List<Candidate> visible = allowed.subList(0, Math.min(limit, allowed.size()));
                    workspaces.applyContext(workspaceId);
                    List<PublishedResultView> items =
                            readViews(
                                    workspaceId,
                                    studentUserId,
                                    visible.stream().map(candidate -> candidate.id()).toList());
                    String next =
                            allowed.size() > limit
                                    ? encodeCursor(
                                            actorUserId,
                                            workspaceId,
                                            studentUserId,
                                            visible.getLast().id(),
                                            limit)
                                    : null;
                    return new ResultPage(items, next);
                });
    }

    private static List<ProtectedActionExecutor.Target> targets(
            UUID workspaceId, List<Candidate> candidates) {
        return candidates.stream()
                .map(candidate ->
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.resultKey(workspaceId, candidate.id()),
                                List.of(READ_PUBLISHED)))
                .toList();
    }

    /** HMAC của actor/workspace/student/anchor ngăn replay cursor qua các ngữ cảnh khác. */
    private String encodeCursor(UUID actor, UUID workspace, UUID student, UUID id, int limit) {
        String message = actor + "|" + workspace + "|" + student + "|" + limit + "|" + id;
        return id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(message));
    }

    private @Nullable UUID decodeCursor(
            @Nullable String cursor, UUID actor, UUID workspace, UUID student, int limit) {
        if (cursor == null) return null;
        if (cursor.length() > MAX_CURSOR_LENGTH) throw invalidCursor();
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != CURSOR_PART_COUNT) throw invalidCursor();
        try {
            UUID id = UUID.fromString(parts[0]);
            if (!id.toString().equals(parts[0])) throw invalidCursor();
            byte[] provided = Base64.getUrlDecoder().decode(parts[1]);
            byte[] actual = sign(actor + "|" + workspace + "|" + student + "|" + limit + "|" + id);
            if (!MessageDigest.isEqual(actual, provided)) throw invalidCursor();
            return id;
        } catch (IllegalArgumentException ex) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_CURSOR,
                    "Cursor danh sách không hợp lệ.",
                    ex);
        }
    }

    private byte[] sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign result page cursor", ex);
        }
    }

    private static HttpProblemException invalidCursor() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.INVALID_CURSOR,
                "Cursor danh sách không hợp lệ.");
    }

    private static List<PublishedResultView> emptyOrDenied(UUID actorUserId, UUID studentUserId) {
        if (actorUserId.equals(studentUserId)) {
            return List.of();
        }
        throw new HttpProblemException(
                HttpStatus.FORBIDDEN,
                ErrorCodes.PERMISSION_DENIED,
                "Không có quyền xem kết quả đã công bố của học sinh này.");
    }

    private List<PublishedResultView> readViews(
            UUID workspaceId, UUID studentUserId, List<UUID> resultIds) {
        String placeholders = String.join(", ", Collections.nCopies(resultIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.add(studentUserId);
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
                        + " WHERE r.workspace_id = ? AND r.student_user_id = ?"
                        + " AND r.state = 'PUBLISHED' AND r.id IN ("
                        + placeholders
                        + ")"
                        + " ORDER BY r.published_at DESC, r.id DESC",
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
