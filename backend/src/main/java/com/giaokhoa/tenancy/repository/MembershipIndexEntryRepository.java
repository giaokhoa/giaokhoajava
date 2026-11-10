package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.MembershipIndexEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipIndexEntryRepository
        extends JpaRepository<MembershipIndexEntry, MembershipIndexEntry.Key> {}
