// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class NotificationDTO {
    private Long id;
    private String type;
    private String projectId;
    private String projectTitle;
    private String badgeCode;
    private String badgeName;
    private String latestActorUsername;
    private int count;
    private boolean read;
    private Date updatedDate;
}
