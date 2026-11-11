package com.giaokhoa.resources.repository;

import com.giaokhoa.identity.model.UserAccount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Điểm đọc của module resources trên entity {@code user_account} thuộc module identity (subject
 * Keycloak cho descriptor của projector).
 */
public interface ResourcesUserAccountRepository extends Repository<UserAccount, UUID> {

    Optional<UserAccount> findById(UUID id);

    List<UserAccount> findByIdIn(Collection<UUID> ids);
}
