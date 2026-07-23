package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    // Every item that belongs to the project (whether in trails or not).
    List<Item> findByProjectId(Long projectId);
}
