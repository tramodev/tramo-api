package com.tramo.backend.user.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UpdatePreferencesRequestDTO {

    @Pattern(regexp = "public|private", message = "profileVisibility must be 'public' or 'private'")
    private String profileVisibility;

    @Pattern(regexp = "off|daily|weekly", message = "emailDigestFrequency must be 'off', 'daily', or 'weekly'")
    private String emailDigestFrequency;

    private Boolean showUpvotes;

    private Boolean showAge;

    private Boolean allowForks;

    @Pattern(regexp = "everyone|following|noone", message = "commentsPolicy must be 'everyone', 'following', or 'noone'")
    private String commentsPolicy;

    private Boolean editorTourSeen;

    private Boolean notificationsEnabled;

    private List<String> mutedNotificationTypes;
}
