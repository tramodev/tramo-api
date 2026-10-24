// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.UserBadge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_badge (id, user_id, badge_code, earned_at) "
            + "VALUES (nextval('user_badge_seq'), :userId, :badgeCode, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_id, badge_code) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("badgeCode") String badgeCode);

    List<UserBadge> findByUserId(Long userId);

    boolean existsByUserIdAndBadgeCode(Long userId, String badgeCode);

    @Query("select count(b) from UserBadge b where b.user.id = :userId and b.badgeCode in :badgeCodes")
    long countByUserIdAndBadgeCodeIn(@Param("userId") Long userId,
                                     @Param("badgeCodes") Collection<String> badgeCodes);
    @Modifying(flushAutomatically = true)
    @Query("delete from UserBadge b where b.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
