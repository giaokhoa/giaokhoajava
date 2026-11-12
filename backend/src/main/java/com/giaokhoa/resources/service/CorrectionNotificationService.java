package com.giaokhoa.resources.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Thông báo đính chính: khi nguồn phát hành một {@code correction_notice}, mỗi workspace từng nhận
 * version bị lỗi nhận đúng một {@code notification_receipt}. Receipt chỉ là dữ kiện thông báo gồm
 * định danh notice và nơi nhận. Nó không mang nội dung, không tham chiếu nguồn quyền nào và không
 * tự cấp quyền xem version thay thế.
 *
 * <p>Tập nơi nhận chốt tại thời điểm phát hành từ các bề mặt đã nhận version đó:
 *
 * <ul>
 *   <li>mục thư viện đã lưu đúng version
 *   <li>lượt cấp share của đúng version còn {@code OFFERED} hoặc đã {@code ACCEPTED}
 *   <li>phân công tài nguyên {@code ACTIVE} khớp resource hoặc môn của resource
 *   <li>hoạt động đã giao trên đúng version
 *   <li>workspace đang giữ bản sao hoặc bản phái sinh của version theo cạnh provenance
 * </ul>
 *
 * <p>Workspace sở hữu nguồn bị loại vì nguồn không tự thông báo cho chính mình. Việc tính tập nơi
 * nhận đọc xuyên workspace theo đúng tiền lệ {@code
 * ResourceRevocationContributor.affectedWorkspaces}.
 *
 * <p>Xác nhận đã biết đính chính không có action riêng trong danh mục: mốc {@code acknowledged_at}
 * được ghi khi nơi nhận chủ động nhận version thay thế của notice qua {@code RECEIVE_UPDATE} — đúng
 * khoảnh khắc bên nhận hành động trên thông báo — và chỉ ghi một lần theo guard của V025.
 */
@Service
public class CorrectionNotificationService {

    private final JdbcTemplate jdbc;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} dựng sẵn, cùng mẫu EI_EXPOSE_REP2 của
     * các service họ resource; service không giữ tham chiếu tới đối tượng mutable từ bên ngoài.
     */
    public CorrectionNotificationService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Notice/receipt của đúng recipient, không chứa quyền đọc nội dung hoặc giấy phép replacement.
     */
    public record RecipientNotice(
            UUID id,
            UUID resourceId,
            UUID affectedVersionId,
            UUID replacementVersionId,
            String reason,
            OffsetDateTime createdAt,
            @Nullable OffsetDateTime acknowledgedAt) {}

    /** Dữ kiện ứng viên nội bộ: controller PHẢI PDP-filter trước khi trả bất kỳ phần tử nào. */
    public List<RecipientNotice> receivedNotices(UUID recipientWorkspaceId) {
        return jdbc.query(
                "SELECT n.id, n.resource_id, n.affected_version_id, n.replacement_version_id,"
                        + " n.reason, n.created_at, receipt.acknowledged_at"
                        + " FROM notification_receipt receipt"
                        + " JOIN correction_notice n ON n.id = receipt.notice_id"
                        + " WHERE receipt.recipient_workspace_id = ?"
                        + " ORDER BY receipt.created_at DESC, n.id DESC",
                (rs, _row) ->
                        new RecipientNotice(
                                Objects.requireNonNull(rs.getObject("id", UUID.class), "notice_id"),
                                Objects.requireNonNull(
                                        rs.getObject("resource_id", UUID.class), "resource_id"),
                                Objects.requireNonNull(
                                        rs.getObject("affected_version_id", UUID.class),
                                        "affected_version_id"),
                                Objects.requireNonNull(
                                        rs.getObject("replacement_version_id", UUID.class),
                                        "replacement_version_id"),
                                Objects.requireNonNull(rs.getString("reason"), "reason"),
                                Objects.requireNonNull(
                                        rs.getObject("created_at", OffsetDateTime.class),
                                        "created_at"),
                                rs.getObject("acknowledged_at", OffsetDateTime.class)),
                recipientWorkspaceId);
    }

    /**
     * Mọi workspace từng nhận version bị ảnh hưởng, trừ workspace sở hữu nguồn, theo thứ tự ổn
     * định. Chỉ đọc dữ kiện bền tại thời điểm phát hành; không suy thêm nơi nhận từ quan hệ khác.
     */
    public List<UUID> affectedRecipients(
            UUID resourceId, UUID affectedVersionId, UUID subjectId, UUID ownerWorkspaceId) {
        return jdbc
                .queryForList(
                        "SELECT DISTINCT recipient FROM ("
                                + " SELECT workspace_id AS recipient FROM library_item"
                                + " WHERE resource_id = ? AND version_id = ?"
                                + " UNION SELECT recipient_workspace_id FROM share_grant"
                                + " WHERE source_resource_id = ? AND source_version_id = ?"
                                + " AND state IN ('OFFERED', 'ACCEPTED')"
                                + " UNION SELECT workspace_id FROM resource_assignment"
                                + " WHERE state = 'ACTIVE'"
                                // Nhánh trực tiếp khớp theo resource bất kể subject, nhánh theo môn
                                // chỉ khi resource_id IS NULL — cùng quy tắc liệt kê target của
                                // ResourcePolicyProjector.allBundleTargetKeys.
                                + " AND (resource_id = ?"
                                + " OR (resource_id IS NULL AND subject_id = ?))"
                                + " UNION SELECT workspace_id FROM sample_activity"
                                + " WHERE resource_id = ? AND version_id = ?"
                                + " UNION SELECT r.owner_workspace_id FROM resource_dependency d"
                                + " JOIN resource r ON r.id = d.derived_resource_id"
                                + " WHERE d.source_resource_id = ? AND d.source_version_id = ?)"
                                + " recipients WHERE recipient <> ?",
                        UUID.class,
                        resourceId,
                        affectedVersionId,
                        resourceId,
                        affectedVersionId,
                        resourceId,
                        subjectId,
                        resourceId,
                        affectedVersionId,
                        resourceId,
                        affectedVersionId,
                        ownerWorkspaceId)
                .stream()
                .sorted()
                .toList();
    }

    /**
     * Ghi một receipt cho từng nơi nhận của notice trong cùng transaction với notice: khóa chính
     * cặp (notice, nơi nhận) khóa luôn ràng buộc một receipt cho mỗi nơi nhận, và {@code ON
     * CONFLICT DO NOTHING} giữ phép ghi an toàn khi tính lại tập nơi nhận.
     */
    public void notifyRecipients(UUID noticeId, List<UUID> recipients) {
        jdbc.batchUpdate(
                "INSERT INTO notification_receipt(notice_id, recipient_workspace_id)"
                        + " VALUES (?,?) ON CONFLICT DO NOTHING",
                recipients,
                recipients.size(),
                (statement, recipient) -> {
                    statement.setObject(1, noticeId);
                    statement.setObject(2, recipient);
                });
    }

    /**
     * Ghi mốc xác nhận cho mọi notice mà version thay thế này là bản đính chính, tại workspace vừa
     * chủ động nhận nó: chỉ các receipt chưa xác nhận được ghi, mốc đã ghi không bao giờ bị xóa hay
     * dời theo đúng guard lịch sử của V025.
     */
    public void acknowledgeReceivedUpdate(
            UUID workspaceId, UUID resourceId, UUID replacementVersionId) {
        jdbc.update(
                "UPDATE notification_receipt SET acknowledged_at = now()"
                        + " WHERE recipient_workspace_id = ? AND acknowledged_at IS NULL"
                        + " AND notice_id IN (SELECT id FROM correction_notice"
                        + " WHERE resource_id = ? AND replacement_version_id = ?)",
                workspaceId,
                resourceId,
                replacementVersionId);
    }
}
