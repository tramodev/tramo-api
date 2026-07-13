package com.mypath.backend.user.repository;

import com.mypath.backend.user.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    long countByFollowedId(Long followedId);
    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);
}
