package com.tramo.backend.tag.repository;

import com.tramo.backend.tag.entity.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    @Query("SELECT t FROM Tag t WHERE (t.official = true OR t.usageCount >= :threshold) "
            + "AND (:q = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY t.official DESC, t.usageCount DESC, t.name ASC")
    List<Tag> findVisibleMatching(@Param("q") String q, @Param("threshold") long threshold, Pageable pageable);

    @Query("SELECT t FROM Tag t WHERE t.official = true OR t.usageCount >= :threshold "
            + "ORDER BY t.usageCount DESC, t.name ASC")
    List<Tag> findVisibleByUsage(@Param("threshold") long threshold, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount + 1 WHERE t.id IN :ids")
    void incrementUsageCount(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Tag t SET t.usageCount = CASE WHEN t.usageCount > 0 THEN t.usageCount - 1 ELSE 0 END WHERE t.id IN :ids")
    void decrementUsageCount(@Param("ids") Collection<Long> ids);
}
