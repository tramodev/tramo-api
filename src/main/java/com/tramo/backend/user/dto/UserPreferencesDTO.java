package com.tramo.backend.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPreferencesDTO {
    private String profileVisibility;
    private String emailDigestFrequency;
    private boolean showUpvotes;
    private boolean allowForks;
    private String commentsPolicy;
}
