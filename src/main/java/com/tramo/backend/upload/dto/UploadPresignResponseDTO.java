package com.tramo.backend.upload.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UploadPresignResponseDTO {
    private String uploadUrl;
    private String publicUrl;
}
