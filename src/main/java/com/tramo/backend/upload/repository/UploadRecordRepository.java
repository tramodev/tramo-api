package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.UploadRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UploadRecordRepository extends JpaRepository<UploadRecord, Long> {
    Optional<UploadRecord> findByObjectKey(String objectKey);

    @Transactional
    void deleteByObjectKey(String objectKey);

    @Query("SELECT COALESCE(SUM(u.bytes), 0) FROM UploadRecord u WHERE u.userId = :userId")
    long sumBytesByUserId(@Param("userId") Long userId);
}
