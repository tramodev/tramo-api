package com.tramo.backend.content.repository;

import com.tramo.backend.content.entity.RoadmapItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RoadmapItemRepository extends JpaRepository<RoadmapItem, Long> {
    List<RoadmapItem> findAllByOrderByPositionAscIdAsc();
    List<RoadmapItem> findByPublishedTrueOrderByPositionAscIdAsc();
}
