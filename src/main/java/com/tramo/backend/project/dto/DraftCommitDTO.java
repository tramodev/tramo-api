package com.tramo.backend.project.dto;

public record DraftCommitDTO(String projectId, Long trailId, Long itemId, long restoreEpoch,
        String projectTitle, String trailTitle, String itemTitle) {}
