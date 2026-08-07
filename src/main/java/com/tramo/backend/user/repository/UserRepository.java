package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);
    @Query("SELECT u FROM User u WHERE :q = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY u.id ASC")
    List<User> searchByUsernameOrEmail(@Param("q") String q, Pageable pageable);
    Optional<User> findByPatreonUserId(String patreonUserId);
    List<User> findByVisibilityTrueAndBannedFalse();
}
