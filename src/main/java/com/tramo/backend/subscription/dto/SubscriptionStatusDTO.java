package com.tramo.backend.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubscriptionStatusDTO {
    private boolean premium;
    private long storageUsedBytes;
    private long storageQuotaBytes;
    private long publishesUsedThisWeek;
    private long publishesPerWeek;  // -1 = unlimited
}
