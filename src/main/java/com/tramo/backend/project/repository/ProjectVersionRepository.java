package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectVersion;
import com.tramo.backend.project.dto.ProjectVersionSummaryDTO;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ProjectVersionRepository extends JpaRepository<ProjectVersion, Long> {
    @Query("select new com.tramo.backend.project.dto.ProjectVersionSummaryDTO(v.id, v.createdAt, v.type, v.name) "
            + "from ProjectVersion v where v.project.id = :projectId and (:beforeId is null or v.id < :beforeId) order by v.id desc")
    List<ProjectVersionSummaryDTO> summaries(Long projectId, Long beforeId, Pageable pageable);
    @Query("select v.stateHash from ProjectVersion v where v.project.id = :projectId order by v.id desc")
    List<String> latestHashes(Long projectId, Pageable pageable);
    Optional<ProjectVersion> findByIdAndProjectId(Long id, Long projectId);
}
