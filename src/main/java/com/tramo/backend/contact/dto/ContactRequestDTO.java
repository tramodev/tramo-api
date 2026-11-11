// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContactRequestDTO {

    @NotBlank(message = "EMAIL_REQUIRED")
    @Email(message = "EMAIL_INVALID")
    @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "EMAIL_INVALID")
    @Size(max = 254, message = "EMAIL_INVALID")
    private String email;

    @NotBlank(message = "TOPIC_REQUIRED")
    @Pattern(regexp = "^(BUG|FEEDBACK|BILLING|OTHER)$", message = "TOPIC_INVALID")
    private String topic;

    @NotBlank(message = "MESSAGE_REQUIRED")
    @Size(max = 2000, message = "MESSAGE_SIZE_INVALID")
    private String message;

    @NotBlank(message = "CAPTCHA_REQUIRED")
    private String captchaToken;
}
