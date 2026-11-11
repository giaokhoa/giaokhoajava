package com.giaokhoa.authorization.fence;

import com.giaokhoa.authorization.fence.DependencyManifestResolver.Manifest;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction fence của một protected request. Việc lấy snapshot khóa mọi partition của manifest
 * bằng {@code FOR SHARE} theo một thứ tự ổn định trong một transaction ngắn, xác nhận từng
 * partition đang READY tại applied revision của nó và ghi lại các revision; khóa được nhả khi
 * commit, nên không transaction nào bị giữ qua một lời gọi mạng. Nếu dependency closure đã dịch
 * chuyển trong lúc resolve, bước lấy snapshot sẽ resolve lại và thử lại thay vì khóa một tập đã cũ.
 * Trước khi caller materialize dữ liệu, {@link #atSnapshot} khóa lại chính các dòng đó và chứng
 * minh các revision đã ghi không đổi; một lần thu hồi commit chen giữa sẽ bump revision hoặc fence
 * một partition nên luôn bị nhìn thấy. Mọi partition PENDING hoặc FAILED đều fail closed với 503.
 */
@Component
public class AuthorizationFence {
    static final int MAX_RESOLUTION_ATTEMPTS = 3;

    private final AuthzPartitionRepository partitions;
    private final DependencyManifestResolver resolver;
    private final TransactionTemplate transactions;

    public AuthorizationFence(
            AuthzPartitionRepository partitions,
            DependencyManifestResolver resolver,
            PlatformTransactionManager transactionManager) {
        this.partitions = partitions;
        this.resolver = resolver;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Manifest kèm desired revision mà từng partition của nó đã được xác minh tại đó. */
    public record Snapshot(Manifest manifest, Map<UUID, Long> revisions) {
        public Snapshot {
            revisions = Map.copyOf(Objects.requireNonNull(revisions, "revisions"));
        }
    }

    /** Closure hoặc một revision đã đổi sau snapshot; caller phải resolve lại. */
    public static final class ClosureChangedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Khóa và xác minh manifest, resolve lại tối đa một số lần giới hạn nếu nó đã dịch chuyển. */
    public Snapshot acquire(Manifest requested) {
        Manifest manifest = requested;
        for (int attempt = 1; ; attempt++) {
            Manifest current = manifest;
            try {
                return transactions.execute(_ -> lockAndVerify(current, null));
            } catch (ClosureChangedException changed) {
                if (attempt >= MAX_RESOLUTION_ATTEMPTS) {
                    throw notReady(changed);
                }
                manifest =
                        resolver.resolve(
                                manifest.actorUserId(),
                                manifest.workspaceId(),
                                manifest.targetKeys());
            }
        }
    }

    /**
     * Chạy {@code body} trong một transaction khóa lại các partition của snapshot và xác minh lại
     * các revision đã ghi của chúng, để việc giữ revision kéo dài tới khi transaction này commit.
     */
    public <T> T atSnapshot(Snapshot snapshot, Supplier<T> body) {
        return transactions.execute(_ -> {
            lockAndVerify(snapshot.manifest(), snapshot.revisions());
            return body.get();
        });
    }

    private Snapshot lockAndVerify(Manifest manifest, @Nullable Map<UUID, Long> expected) {
        // Một query khóa chia sẻ mọi partition của manifest theo thứ tự id ổn định, rồi state
        // và revision được đối chiếu trong bộ nhớ theo đúng thứ tự manifest.
        Map<UUID, AuthorizationPartition> locked = new HashMap<>();
        List<UUID> partitionIds = manifest.partitionIds();
        if (!partitionIds.isEmpty()) {
            for (AuthorizationPartition partition : partitions.lockAllShared(partitionIds)) {
                locked.put(partition.getId(), partition);
            }
        }
        Map<UUID, Long> revisions = new LinkedHashMap<>();
        for (UUID partitionId : partitionIds) {
            AuthorizationPartition partition = locked.get(partitionId);
            if (partition == null) {
                throw notReady();
            }
            long desired = partition.getDesiredRevision();
            long applied = partition.getAppliedRevision();
            if (partition.getState() != AuthorizationPartition.State.READY || desired != applied) {
                throw notReady();
            }
            if (expected != null && !Objects.equals(expected.get(partitionId), desired)) {
                throw new ClosureChangedException();
            }
            revisions.put(partitionId, desired);
        }
        if (expected == null) {
            // Resolve lại dưới khóa: quyết định trên một closure đã dịch chuyển là bị cấm
            // ngay cả khi mọi dòng đã khóa vẫn đọc là READY.
            Manifest fresh =
                    resolver.resolve(
                            manifest.actorUserId(), manifest.workspaceId(), manifest.targetKeys());
            if (!fresh.partitionIds().equals(manifest.partitionIds())) {
                throw new ClosureChangedException();
            }
        }
        return new Snapshot(manifest, revisions);
    }

    /** Lỗi 503 chung cho mọi thất bại của fence: PENDING/FAILED hoặc closure không chịu ổn định. */
    public static HttpProblemException notReady() {
        return notReady(null);
    }

    public static HttpProblemException notReady(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.AUTHORIZATION_NOT_READY,
                DependencyManifestResolver.NOT_READY_DETAIL,
                cause);
    }
}
