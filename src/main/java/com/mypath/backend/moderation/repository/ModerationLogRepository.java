package com.mypath.backend.moderation.repository;

import com.mypath.backend.moderation.entity.ModerationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {
}
