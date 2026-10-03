package com.tramo.backend.upload.dto;

import java.util.UUID;

public record EditorImagePresignResponse(UUID imageId, String uploadUrl) {}
