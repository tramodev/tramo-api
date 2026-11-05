// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.controller;

import com.tramo.backend.collab.dto.CollabTokenDTO;
import com.tramo.backend.collab.dto.CollabTokenRequest;
import com.tramo.backend.collab.service.CollabTokenService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/collab")
public class CollabController {
    private final CollabTokenService tokens;

    public CollabController(CollabTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/token")
    public CollabTokenDTO token(@Valid @RequestBody CollabTokenRequest request, @AuthenticationPrincipal User user) {
        return tokens.issue(request.itemId(), user);
    }
}
