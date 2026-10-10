// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;

public enum RequestErrorCode {
    TITLE_REQUIRED("Title is required"),
    PROJECT_DESCRIPTION_REQUIRED("Add a description before publishing"),
    PROJECT_PUBLISH_AFTER_CREATE("Publish the project after creating it"),
    CURRENT_PASSWORD_INCORRECT("Current password is incorrect"),
    THUMBNAIL_TRAIL_REQUIRED("trailId is required for GRAPH thumbnail"),
    PRIVATE_THUMBNAIL_FORBIDDEN("Note images cannot be used as public thumbnails"),
    THUMBNAIL_URL_REQUIRED("imageUrl is required for this thumbnail type"),
    THUMBNAIL_UPLOAD_REQUIRED("Thumbnail must be a separate public upload owned by you"),
    PROJECT_TRAIL_INVALID("Trail does not belong to this project"),
    IMAGE_URL_INVALID("Invalid image URL"),
    BANNER_URL_INVALID("Invalid banner URL"),
    COMMENT_PARENT_INVALID("Parent comment belongs to a different project"),
    TRAIL_ORDER_INVALID("The new order must list every item in the trail exactly once"),
    IMAGE_SIZE_LIMIT_EXCEEDED("Image exceeds upload size limit"),
    EDITOR_CONTENT_INVALID("Invalid editor content"),
    IMAGE_ATTACHMENT_REQUIRED("Images must reference a confirmed private attachment"),
    IMAGE_ID_INVALID("Invalid image identifier"),
    IMAGE_VALIDATION_BUSY("Image validation busy; retry later"),
    IMAGE_UPLOAD_METADATA_MISMATCH("Uploaded image size or type does not match"),
    IMAGE_UPLOAD_SIZE_MISMATCH("Uploaded image size does not match"),
    IMAGE_UPLOAD_HASH_MISMATCH("Uploaded image SHA-256 does not match"),
    IMAGE_UPLOAD_UNREADABLE("Cannot read uploaded image"),
    IMAGE_CONFIRMED_MISMATCH("Confirmed image does not match"),
    IMAGE_DECODING_TIMEOUT("Image decoding time limit exceeded"),
    IMAGE_TRUNCATED("Invalid or truncated image"),
    IMAGE_DIMENSIONS_EXCEEDED("Image dimensions exceed limit"),
    IMAGE_METADATA_BLOCK_LIMIT("Image metadata block limit exceeded"),
    IMAGE_METADATA_BYTE_LIMIT("Image metadata byte limit exceeded"),
    IMAGE_METADATA_INVALID("Invalid compressed image metadata"),
    IMAGE_INVALID("Invalid, unsupported or truncated image"),
    UPLOAD_IN_PROGRESS("Upload operation in progress"),
    UPLOAD_ATTEMPT_EXPIRED("Upload attempt expired"),
    ANIMATED_BANNER_UNSUPPORTED("Animated GIF banners are not supported."),
    USERNAME_REQUIRED("Username is required"),
    USERNAME_SIZE_INVALID("Username must be between 3 and 20 characters"),
    USERNAME_FORMAT_INVALID("Username can only contain letters, numbers and underscores"),
    PASSWORD_REQUIRED("Password is required"),
    PASSWORD_SIZE_INVALID("Password must be between 12 and 40 characters"),
    PASSWORD_FORMAT_INVALID("Password must contain at least one uppercase letter, one number, and one symbol"),
    EMAIL_REQUIRED("Email is required"),
    EMAIL_INVALID("Email must be valid"),
    CAPTCHA_REQUIRED("Captcha verification is required"),
    BIRTH_DATE_REQUIRED("Date of birth is required"),
    BIRTH_DATE_INVALID("Date of birth must be in the past"),
    CURRENT_PASSWORD_REQUIRED("Current password is required"),
    NEW_PASSWORD_REQUIRED("New password is required"),
    TOKEN_REQUIRED("Token is required"),
    ID_TOKEN_REQUIRED("ID token is required"),
    COMMENT_REQUIRED("Comment cannot be empty"),
    THUMBNAIL_TYPE_INVALID("Invalid thumbnail type"),
    BIO_SIZE_INVALID("Bio must be at most 500 characters"),
    LOCATION_SIZE_INVALID("Location must be at most 100 characters"),
    WEBSITE_SIZE_INVALID("Website must be at most 200 characters"),
    PROFILE_VISIBILITY_INVALID("profileVisibility must be 'public' or 'private'"),
    EMAIL_DIGEST_FREQUENCY_INVALID("emailDigestFrequency must be 'off', 'daily', or 'weekly'"),
    COMMENTS_POLICY_INVALID("commentsPolicy must be 'everyone', 'following', or 'noone'"),
    UPLOAD_KIND_INVALID("kind must be avatar, thumbnail, editor-image, or banner"),
    CONTENT_HASH_INVALID("contentHash must be a 64-char lowercase hex SHA-256 digest"),
    INVALID_REQUEST("Invalid request"),
    INVALID_VALUE("Invalid value"),
    NOTIFICATION_TYPE_INVALID("Unknown notification type"),
    CONTENT_TYPE_UNSUPPORTED("Unsupported content type"),
    UPLOAD_SIZE_LIMIT_EXCEEDED("File exceeds the upload size limit"),
    BADGE_NOT_EARNED("Badge not earned"),
    VALUE_REQUIRED("This field is required"),
    VALUE_SIZE_INVALID("Value has an invalid length"),
    VALUE_FORMAT_INVALID("Value has an invalid format"),
    VALUE_POSITIVE_REQUIRED("Value must be positive"),
    VALUE_NON_NEGATIVE_REQUIRED("Value must be zero or positive"),
    VALUE_BELOW_MINIMUM("Value is below the allowed minimum"),
    VALUE_ABOVE_MAXIMUM("Value exceeds the allowed maximum");

    private final String message;

    RequestErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public static RequestErrorCode validation(String messageCode, String constraint) {
        if (messageCode != null) {
            try {
                return valueOf(messageCode);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (constraint == null) return INVALID_VALUE;
        return switch (constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> VALUE_REQUIRED;
            case "Size" -> VALUE_SIZE_INVALID;
            case "Pattern", "Email" -> VALUE_FORMAT_INVALID;
            case "Positive" -> VALUE_POSITIVE_REQUIRED;
            case "PositiveOrZero" -> VALUE_NON_NEGATIVE_REQUIRED;
            case "Min", "DecimalMin" -> VALUE_BELOW_MINIMUM;
            case "Max", "DecimalMax" -> VALUE_ABOVE_MAXIMUM;
            default -> INVALID_VALUE;
        };
    }
}
