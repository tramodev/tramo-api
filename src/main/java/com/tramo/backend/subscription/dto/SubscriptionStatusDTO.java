// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubscriptionStatusDTO {
    private boolean supporter;
    private long storageUsedBytes;
    private long storageQuotaBytes;
    private long publishesPerWeek;  
}
