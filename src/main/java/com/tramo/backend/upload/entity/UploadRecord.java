package com.tramo.backend.upload.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Date;

/** One row per R2 object, written at presign time — powers per-user storage quotas. */
@Entity
@Getter @Setter
@NoArgsConstructor
@Table(indexes = @Index(name = "idx_upload_record_user", columnList = "userId"),
        uniqueConstraints = @UniqueConstraint(name = "uk_upload_record_key", columnNames = "objectKey"))
public class UploadRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private Long userId;
    private String objectKey;
    private long bytes;
    private Date createdDate;
}
