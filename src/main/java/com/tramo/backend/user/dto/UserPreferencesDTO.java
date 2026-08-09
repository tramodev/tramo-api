package com.tramo.backend.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class UserPreferencesDTO {
    private String profileVisibility;
    private String emailDigestFrequency;
    private boolean showUpvotes;
    private boolean showAge;
    private boolean allowForks;
    private String commentsPolicy;
    private boolean editorTourSeen;
    private boolean notificationsEnabled;
    private List<String> mutedNotificationTypes;
}
