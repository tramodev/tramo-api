// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.AcceptShareLinkRequest;
import com.tramo.backend.project.dto.ShareLinkDTO;
import com.tramo.backend.project.dto.ShareLinkRequest;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.service.ProjectShareLinkService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProjectShareLinkController {
    private final ProjectShareLinkService links;
    private final ProjectIdCodec codec;

    public ProjectShareLinkController(ProjectShareLinkService links, ProjectIdCodec codec) {
        this.links = links; this.codec = codec;
    }

    @GetMapping("/project/{id}/share-links")
    public List<ShareLinkDTO> list(@PathVariable String id, @AuthenticationPrincipal User user) {
        return links.list(codec.decode(id), user);
    }

    @PostMapping("/project/{id}/share-links")
    public ShareLinkDTO create(@PathVariable String id, @Valid @RequestBody ShareLinkRequest request, @AuthenticationPrincipal User user) {
        return links.create(codec.decode(id), ProjectRole.valueOf(request.role()), request.expiresAt(), user);
    }

    @DeleteMapping("/project/{id}/share-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable Long linkId, @AuthenticationPrincipal User user) {
        links.revoke(codec.decode(id), linkId, user);
    }

    @PostMapping("/share-links/accept")
    public Map<String, String> accept(@Valid @RequestBody AcceptShareLinkRequest request, @AuthenticationPrincipal User user) {
        return Map.of("projectId", links.accept(request.token(), user));
    }
}
