package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SystemPracticeAttemptSample;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemPracticeAttemptRepository
        extends JpaRepository<SystemPracticeAttemptSample, UUID> {

    List<SystemPracticeAttemptSample> findByUserIdOrderByAttemptedAtDescIdDesc(UUID userId);
}
