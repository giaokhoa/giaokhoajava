package com.giaokhoa.authorization.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Phần fragment của {@link AuthzProjectionRepository} cho điểm truy vấn giữ SQL native. */
public interface AuthzProjectionRepositoryCustom {

    /** Một projection READY phụ thuộc ít nhất một partition trong tập cho trước. */
    record DependentProjection(
            UUID projectionId, UUID partitionId, List<UUID> dependencyPartitionIds) {
        public DependentProjection {
            dependencyPartitionIds = List.copyOf(dependencyPartitionIds);
        }
    }

    /**
     * Mọi projection READY phụ thuộc ít nhất một partition trong tập cho trước: bước mở rộng bao
     * đóng phụ thuộc của đường ghi desired state.
     */
    List<DependentProjection> findReadyDependentsOfAny(Collection<UUID> partitionIds);
}
