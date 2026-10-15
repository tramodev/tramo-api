package com.tramo.backend.content.repository;

import com.tramo.backend.content.entity.ChangelogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChangelogEntryRepository extends JpaRepository<ChangelogEntry, Long> {
    List<ChangelogEntry> findAllByOrderByPublishedDateDescIdDesc();
    List<ChangelogEntry> findByPublishedTrueOrderByPublishedDateDescIdDesc();
}
