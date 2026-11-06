// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.controller;

import com.tramo.backend.collab.dto.CollabProjectTokenRequest;
import com.tramo.backend.collab.dto.CollabTokenDTO;
import com.tramo.backend.collab.dto.CollabTokenRequest;
import com.tramo.backend.collab.service.CollabTokenService;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/collab")
public class CollabController {
    private final CollabTokenService tokens;
    private final ProjectIdCodec projectIdCodec;

    public CollabController(CollabTokenService tokens, ProjectIdCodec projectIdCodec) {
        this.tokens = tokens;
        this.projectIdCodec = projectIdCodec;
    }

    @PostMapping("/token")
    public CollabTokenDTO token(@Valid @RequestBody CollabTokenRequest request, @AuthenticationPrincipal User user) {
        return tokens.issue(request.itemId(), user);
    }

    @PostMapping("/project-token")
    public CollabTokenDTO projectToken(@Valid @RequestBody CollabProjectTokenRequest request, @AuthenticationPrincipal User user) {
        return tokens.issueProject(projectIdCodec.decode(request.projectId()), user);
    }
}
