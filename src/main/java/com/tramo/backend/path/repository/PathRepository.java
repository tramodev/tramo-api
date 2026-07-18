package com.tramo.backend.path.repository;

import com.tramo.backend.path.entity.Path;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PathRepository extends JpaRepository<Path, Long> {
    List<Path> findByProjectId(Long projectId);
}
