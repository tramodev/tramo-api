package com.tramo.backend.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadPresignRequestDTO {
    @NotBlank
    private String contentType;

    @NotBlank
    @Pattern(regexp = "avatar|thumbnail|editor-image|banner", message = "UPLOAD_KIND_INVALID")
    private String kind;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}", message = "CONTENT_HASH_INVALID")
    private String contentHash;

    
    
    @NotNull
    @Positive
    private Long contentBytes;

    
    private String projectId;
}
