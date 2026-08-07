package com.tramo.backend.tag.repository;

import com.tramo.backend.tag.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount + 1 WHERE t.id IN :ids")
    void incrementUsageCount(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Tag t SET t.usageCount = CASE WHEN t.usageCount > 0 THEN t.usageCount - 1 ELSE 0 END WHERE t.id IN :ids")
    void decrementUsageCount(@Param("ids") Collection<Long> ids);
}
