// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.UploadRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;

public interface UploadRecordRepository extends JpaRepository<UploadRecord, Long> {
    Optional<UploadRecord> findByObjectKey(String objectKey);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM public_upload u JOIN upload_record r ON r.id = u.upload_record_id " +
            "WHERE u.owner_id = :ownerId AND r.user_id = :ownerId AND r.object_key = :key AND u.state = 'READY')", nativeQuery = true)
    boolean isConfirmedPublicKey(@Param("key") String key, @Param("ownerId") Long ownerId);

    @Query(value = "SELECT u.id FROM public_upload u JOIN upload_record r ON r.id = u.upload_record_id WHERE r.object_key = :key", nativeQuery = true)
    Optional<UUID> findPublicUploadIdByObjectKey(@Param("key") String key);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from UploadRecord r where r.objectKey = :objectKey")
    void deleteByObjectKey(@Param("objectKey") String objectKey);

    @Query("SELECT COALESCE(SUM(u.bytes), 0) FROM UploadRecord u WHERE u.userId = :userId")
    long sumBytesByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT (SELECT COALESCE(SUM(bytes), 0) FROM upload_record WHERE user_id = :userId) + " +
            "(SELECT COALESCE(SUM(octet_length(c.content)), 0) FROM item_content c WHERE EXISTS " +
            "(SELECT 1 FROM item i WHERE i.content_id = c.id AND (EXISTS " +
            "(SELECT 1 FROM project p WHERE p.id = i.project_id AND p.user_id = :userId) OR EXISTS " +
            "(SELECT 1 FROM trail_item ti JOIN trail t ON t.id = ti.trail_id JOIN project p ON p.id = t.project_id " +
            "WHERE ti.item_id = i.id AND p.user_id = :userId)))) + " +
            "(SELECT COALESCE(SUM(octet_length(t.description)), 0) FROM trail t JOIN project p ON p.id = t.project_id " +
            "WHERE p.user_id = :userId) + " +
            "(SELECT COALESCE(SUM(octet_length(s.content)), 0) FROM project_snapshot s JOIN project p ON p.id = s.project_id " +
            "WHERE p.user_id = :userId)", nativeQuery = true)
    long sumStorageBytesByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(u.bytes), 0) FROM UploadRecord u WHERE u.projectId = :projectId")
    long sumBytesByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT u.projectId AS projectId, SUM(u.bytes) AS bytes FROM UploadRecord u WHERE u.projectId IN :projectIds GROUP BY u.projectId")
    List<ProjectBytesSum> sumBytesGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectBytesSum {
        Long getProjectId();
        Long getBytes();
    }
}
