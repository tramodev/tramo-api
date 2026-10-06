package com.tramo.backend.user.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UpdatePreferencesRequestDTO {

    @Pattern(regexp = "public|private", message = "PROFILE_VISIBILITY_INVALID")
    private String profileVisibility;

    @Pattern(regexp = "off|daily|weekly", message = "EMAIL_DIGEST_FREQUENCY_INVALID")
    private String emailDigestFrequency;

    private Boolean showUpvotes;

    private Boolean showAge;

    private Boolean allowForks;

    @Pattern(regexp = "everyone|following|noone", message = "COMMENTS_POLICY_INVALID")
    private String commentsPolicy;

    private Boolean editorTourSeen;

    private Boolean notificationsEnabled;

    private List<String> mutedNotificationTypes;
}
