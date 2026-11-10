package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleActionMutation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SampleActionMutationRepository
        extends JpaRepository<SampleActionMutation, SampleActionMutation.Id>, SampleActionClaim {}
