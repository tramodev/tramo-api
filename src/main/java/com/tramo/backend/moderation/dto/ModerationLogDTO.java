package com.tramo.backend.moderation.dto;

import java.util.Date;

public record ModerationLogDTO(Long id, String adminUsername, String action, String targetType,
        String targetId, String reason, Date createdDate, boolean publishingBlocked) {}
