// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.ModerationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {
    @Query("SELECT m.id, a.username, m.action, m.targetType, m.targetId, m.reason, m.createdDate, COALESCE(p.moderationBlocked, false) "
            + "FROM ModerationLog m LEFT JOIN m.admin a LEFT JOIN Project p ON m.targetType = 'PROJECT' AND p.id = m.targetId "
            + "ORDER BY m.createdDate DESC, m.id DESC")
    Slice<Object[]> findRows(Pageable pageable);

    @Modifying
    @Query("UPDATE ModerationLog m SET m.admin = null WHERE m.admin.id = :adminId")
    void clearAdminReferences(@Param("adminId") Long adminId);
}
