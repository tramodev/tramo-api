package com.tramo.backend.upload.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EditorImageResolveResponse(List<Image> images) {
    public record Image(UUID imageId, String url, Instant expiresAt) {}
}
