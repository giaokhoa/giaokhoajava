package com.giaokhoa.verification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.tenancy.WorkspaceDirectory;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Đọc lịch sử luyện tập trên resource hệ thống của chính người dùng. Bảng mẫu nằm dưới RLS theo
 * personal workspace nên truy vấn chạy trong WorkspaceTransaction với context là personal workspace
 * của actor, và vẫn lọc thêm theo user_id của actor để hai lớp phạm vi cùng giữ.
 */
@Service
public class SystemPracticeService {
    private final JdbcTemplate jdbc;
    private final WorkspaceDirectory directory;
    private final WorkspaceTransaction workspaceTransaction;

    public SystemPracticeService(
            JdbcTemplate jdbc,
            WorkspaceDirectory directory,
            WorkspaceTransaction workspaceTransaction) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.workspaceTransaction = workspaceTransaction;
    }

    public record PracticeEntry(
            UUID id,
            Instant attemptedAt,
            UUID systemResourceId,
            UUID versionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String outcomeStub) {}

    /** Lịch sử của actor, lần thử mới nhất trước; chưa có personal workspace thì danh sách rỗng. */
    public List<PracticeEntry> historyFor(UUID actorUserId) {
        Optional<UUID> personalWorkspace = directory.personalWorkspace(actorUserId);
        if (personalWorkspace.isEmpty()) {
            return List.of();
        }
        List<PracticeEntry> entries =
                workspaceTransaction.inWorkspace(
                        personalWorkspace.get(),
                        () ->
                                jdbc.query(
                                        "SELECT id, attempted_at, system_resource_id, version_id,"
                                                + " outcome_stub FROM"
                                                + " system_practice_attempt_sample"
                                                + " WHERE user_id = ?"
                                                + " ORDER BY attempted_at DESC, id DESC",
                                        (rs, _) ->
                                                new PracticeEntry(
                                                        rs.getObject("id", UUID.class),
                                                        rs.getTimestamp("attempted_at").toInstant(),
                                                        rs.getObject(
                                                                "system_resource_id", UUID.class),
                                                        rs.getObject("version_id", UUID.class),
                                                        rs.getString("outcome_stub")),
                                        actorUserId));
        return entries == null ? List.of() : entries;
    }
}
