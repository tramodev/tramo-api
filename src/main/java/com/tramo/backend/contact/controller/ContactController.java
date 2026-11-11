// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.contact.controller;

import com.tramo.backend.auth.service.CaptchaVerifier;
import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.contact.dto.ContactRequestDTO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/contact")
public class ContactController {
    private final CaptchaVerifier captchaVerifier;
    private final EmailService emailService;

    public ContactController(CaptchaVerifier captchaVerifier, EmailService emailService) {
        this.captchaVerifier = captchaVerifier;
        this.emailService = emailService;
    }

    @PostMapping
    public ResponseEntity<Void> send(@Valid @RequestBody ContactRequestDTO request) {
        captchaVerifier.verify(request.getCaptchaToken(), "contact");
        emailService.sendContactEmail(request.getEmail(), request.getTopic(), request.getMessage());
        return ResponseEntity.noContent().build();
    }
}
