package com.giaokhoa.verification.repository;

import com.giaokhoa.identity.model.UserAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc tài khoản qua entity của module identity; repository đầy đủ thuộc identity. */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {}
