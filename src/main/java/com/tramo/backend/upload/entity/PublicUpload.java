package com.tramo.backend.upload.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class PublicUpload {
    @Id
    private UUID id;
    private Long ownerId;
    private Long uploadRecordId;
    private String kind;
    private String contentType;
    private String contentHash;
    private String state;
    private Date requestedAt;
    private boolean temporaryCleaned;
}
