package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.UserBadge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {
    List<UserBadge> findByUserId(Long userId);

    boolean existsByUserIdAndBadgeCode(Long userId, String badgeCode);
    @Modifying(flushAutomatically = true)
    @Query("delete from UserBadge b where b.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
