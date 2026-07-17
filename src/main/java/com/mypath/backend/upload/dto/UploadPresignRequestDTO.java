package com.mypath.backend.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadPresignRequestDTO {
    @NotBlank
    private String contentType;

    @Pattern(regexp = "avatar|thumbnail|editor-image", message = "kind must be avatar, thumbnail, or editor-image")
    private String kind;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}", message = "contentHash must be a 64-char lowercase hex SHA-256 digest")
    private String contentHash;
}
