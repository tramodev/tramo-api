package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.UploadRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;

public interface UploadRecordRepository extends JpaRepository<UploadRecord, Long> {
    Optional<UploadRecord> findByObjectKey(String objectKey);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from UploadRecord r where r.objectKey = :objectKey")
    void deleteByObjectKey(@Param("objectKey") String objectKey);

    @Query("SELECT COALESCE(SUM(u.bytes), 0) FROM UploadRecord u WHERE u.userId = :userId")
    long sumBytesByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(u.bytes), 0) FROM UploadRecord u WHERE u.projectId = :projectId")
    long sumBytesByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT u.projectId AS projectId, SUM(u.bytes) AS bytes FROM UploadRecord u WHERE u.projectId IN :projectIds GROUP BY u.projectId")
    List<ProjectBytesSum> sumBytesGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectBytesSum {
        Long getProjectId();
        Long getBytes();
    }
}
