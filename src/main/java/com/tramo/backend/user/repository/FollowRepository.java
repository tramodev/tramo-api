package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.Follow;
import com.tramo.backend.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    long countByFollowedId(Long followedId);
    long countByFollowerId(Long followerId);
    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);
    void deleteByFollowerIdOrFollowedId(Long followerId, Long followedId);

    @Query(value = "SELECT f FROM Follow f JOIN FETCH f.follower WHERE f.followed.id = :userId ORDER BY f.createdDate DESC",
            countQuery = "SELECT COUNT(f) FROM Follow f WHERE f.followed.id = :userId")
    Page<Follow> findByFollowedIdOrderByCreatedDateDesc(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT f FROM Follow f JOIN FETCH f.followed WHERE f.follower.id = :userId ORDER BY f.createdDate DESC",
            countQuery = "SELECT COUNT(f) FROM Follow f WHERE f.follower.id = :userId")
    Page<Follow> findByFollowerIdOrderByCreatedDateDesc(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT f.followed.id FROM Follow f WHERE f.follower.id = :followerId AND f.followed.id IN :followedIds")
    List<Long> findFollowedIdsIn(@Param("followerId") Long followerId, @Param("followedIds") List<Long> followedIds);

    @Query("SELECT f.follower FROM Follow f WHERE f.followed.id = :followedId")
    List<User> findFollowersByFollowedId(@Param("followedId") Long followedId);

    @Query("SELECT f.followed.id FROM Follow f WHERE f.follower.id = :followerId")
    List<Long> findFollowedIds(@Param("followerId") Long followerId);
}
