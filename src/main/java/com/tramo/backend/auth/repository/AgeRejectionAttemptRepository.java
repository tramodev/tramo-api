package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.AgeRejectionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AgeRejectionAttemptRepository extends JpaRepository<AgeRejectionAttempt, Long> {
    boolean existsByIpAddressAndRejectedAtAfter(String ipAddress, Instant cutoff);
}
