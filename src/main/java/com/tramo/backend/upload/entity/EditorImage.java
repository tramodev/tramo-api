package com.tramo.backend.upload.entity;

import java.util.UUID;

public record EditorImage(UUID id, UUID objectId, Long projectId, String contentType, long bytes, String state, String contentHash) {
    public String temporaryKey() { return "temporary/" + objectId; }
    public String objectKey() { return "images/" + objectId; }
}
