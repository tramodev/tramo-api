package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.ItemContent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ItemContentRepository extends JpaRepository<ItemContent, Long> {
    List<ItemContent> findTop100ByWordCountIsNullOrderByIdAsc();
}
