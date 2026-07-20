package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.BlockedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BlockedUserRepository extends JpaRepository<BlockedUser, Long> {
    Optional<BlockedUser> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
    void deleteByBlockerIdOrBlockedId(Long blockerId, Long blockedId);

    @Query(value = "SELECT b FROM BlockedUser b JOIN FETCH b.blocked WHERE b.blocker.id = :userId ORDER BY b.createdDate DESC",
            countQuery = "SELECT COUNT(b) FROM BlockedUser b WHERE b.blocker.id = :userId")
    Page<BlockedUser> findByBlockerIdOrderByCreatedDateDesc(@Param("userId") Long userId, Pageable pageable);

    default boolean existsEitherDirection(Long userA, Long userB) {
        return existsByBlockerIdAndBlockedId(userA, userB) || existsByBlockerIdAndBlockedId(userB, userA);
    }
}
