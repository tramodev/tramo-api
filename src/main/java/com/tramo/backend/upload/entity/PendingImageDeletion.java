package com.tramo.backend.upload.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter @Setter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_pending_image_deletion_url", columnNames = "url"),
        indexes = @Index(name = "idx_pending_image_deletion_requested_at", columnList = "requested_at"))
public class PendingImageDeletion {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String url;
    private Long ownerId;
    private Date requestedAt;
}
