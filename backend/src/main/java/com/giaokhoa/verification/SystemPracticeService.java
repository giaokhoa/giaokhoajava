package com.giaokhoa.verification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.service.WorkspaceDirectoryService;
import com.giaokhoa.verification.repository.SystemPracticeAttemptRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Đọc lịch sử luyện tập trên resource hệ thống của chính người dùng. Bảng mẫu nằm trong schema
 * tenant của personal workspace nên truy vấn chạy trong WorkspaceTransaction với context là
 * personal của actor, và vẫn lọc thêm theo user_id của actor để hai lớp phạm vi cùng giữ.
 */
@Service
public class SystemPracticeService {
    private final SystemPracticeAttemptRepository attempts;
    private final WorkspaceDirectoryService directory;
    private final WorkspaceTransaction workspaceTransaction;

    public SystemPracticeService(
            SystemPracticeAttemptRepository attempts,
            WorkspaceDirectoryService directory,
            WorkspaceTransaction workspaceTransaction) {
        this.attempts = attempts;
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
                                attempts
                                        .findByUserIdOrderByAttemptedAtDescIdDesc(actorUserId)
                                        .stream()
                                        .map(attempt ->
                                                new PracticeEntry(
                                                        attempt.getId(),
                                                        attempt.getAttemptedAt(),
                                                        attempt.getSystemResourceId(),
                                                        attempt.getVersionId(),
                                                        attempt.getOutcomeStub()))
                                        .toList());
        return entries == null ? List.of() : entries;
    }
}
