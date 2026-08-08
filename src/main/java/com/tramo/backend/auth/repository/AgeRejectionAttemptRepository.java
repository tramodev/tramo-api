package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.AgeRejectionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AgeRejectionAttemptRepository extends JpaRepository<AgeRejectionAttempt, Long> {
    boolean existsByIpAddressAndRejectedAtAfter(String ipAddress, Instant cutoff);

    @Modifying(flushAutomatically = true)
    @Query("delete from AgeRejectionAttempt a where a.rejectedAt < :cutoff")
    int deleteByRejectedAtBefore(@Param("cutoff") Instant cutoff);
}
