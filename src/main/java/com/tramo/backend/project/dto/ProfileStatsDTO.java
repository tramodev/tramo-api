// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ProfileStatsDTO {
    private long trailsPublished;
    private long upvotesReceived;
    private long totalViews;
    private long forksCount;
    private long followersCount;
    private long followingCount;
}
