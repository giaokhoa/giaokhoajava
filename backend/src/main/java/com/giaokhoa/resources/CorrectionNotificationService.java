package com.giaokhoa.resources;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Thông báo đính chính của US7 (FR-034): khi nguồn phát hành một {@code correction_notice}, mỗi
 * workspace từng nhận version bị lỗi nhận đúng một {@code notification_receipt} — receipt chỉ là
 * dữ kiện thông báo gồm định danh notice và nơi nhận, không mang nội dung, không tham chiếu nguồn
 * quyền nào và không tự cấp quyền xem version thay thế (data-model của correction). Tập nơi nhận
 * chốt tại thời điểm phát hành từ bốn bề mặt đã nhận version đó: mục thư viện đã lưu đúng version,
 * lượt cấp share của đúng version còn {@code OFFERED} hoặc đã {@code ACCEPTED}, phân công tài
 * nguyên {@code ACTIVE} khớp resource hoặc môn của resource, và hoạt động đã giao trên đúng
 * version; workspace sở hữu nguồn bị loại vì nguồn không tự thông báo cho chính mình. Việc tính
 * tập nơi nhận đọc xuyên workspace theo đúng tiền lệ {@code
 * ResourceRevocationContributor.affectedWorkspaces}.
 *
 * <p>Xác nhận đã biết đính chính không có action riêng trong danh mục: mốc {@code acknowledged_at}
 * được ghi khi nơi nhận chủ động nhận version thay thế của notice qua {@code RECEIVE_UPDATE} —
 * đúng khoảnh khắc bên nhận hành động trên thông báo — và chỉ ghi một lần theo guard của V025.
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
                                + " AND ((resource_id = ? AND (subject_id IS NULL"
                                + " OR subject_id = ?))"
                                + " OR (resource_id IS NULL AND subject_id = ?))"
                                + " UNION SELECT workspace_id FROM sample_activity"
                                + " WHERE resource_id = ? AND version_id = ?)"
                                + " recipients WHERE recipient <> ?",
                        UUID.class,
                        resourceId,
                        affectedVersionId,
                        resourceId,
                        affectedVersionId,
                        resourceId,
                        subjectId,
                        subjectId,
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
        for (UUID recipient : recipients) {
            jdbc.update(
                    "INSERT INTO notification_receipt(notice_id, recipient_workspace_id)"
                            + " VALUES (?,?) ON CONFLICT DO NOTHING",
                    noticeId,
                    recipient);
        }
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
