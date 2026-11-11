package com.giaokhoa.verification.repository;

import com.giaokhoa.resources.model.ResourceDependency;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc cạnh phụ thuộc tài nguyên trên entity của module resources cho các luồng xác thực. */
public interface VerificationResourceDependencyRepository
        extends JpaRepository<ResourceDependency, UUID> {

    /** Nạp một tầng của phép duyệt closure: mọi cạnh có đầu derived thuộc tập version cho trước. */
    List<ResourceDependency> findByDerivedVersionIdIn(Collection<UUID> derivedVersionIds);
}
