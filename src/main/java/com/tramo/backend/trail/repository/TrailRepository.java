package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrailRepository extends JpaRepository<Trail, Long> {
    // Creation order — without an ORDER BY the DB returns rows in arbitrary
    // (reload-dependent) order and the sidebar shuffles.
    @Query("select t from Trail t where t.project.id = :projectId order by t.id asc")
    List<Trail> findByProjectId(@Param("projectId") Long projectId);
}
