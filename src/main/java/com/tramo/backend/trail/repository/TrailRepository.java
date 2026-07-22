package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrailRepository extends JpaRepository<Trail, Long> {
    List<Trail> findByProjectId(Long projectId);
}
