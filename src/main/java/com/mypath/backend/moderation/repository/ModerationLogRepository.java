package com.mypath.backend.moderation.repository;

import com.mypath.backend.moderation.entity.ModerationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {
    @Modifying
    @Query("UPDATE ModerationLog m SET m.admin = null WHERE m.admin.id = :adminId")
    void clearAdminReferences(@Param("adminId") Long adminId);
}
