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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProjectShareLinkController {
    private final ProjectShareLinkService links;
    private final ProjectIdCodec codec;

    public ProjectShareLinkController(ProjectShareLinkService links, ProjectIdCodec codec) {
        this.links = links; this.codec = codec;
    }

    @GetMapping("/project/{id}/share-link")
    public ResponseEntity<ShareLinkDTO> get(@PathVariable String id, @AuthenticationPrincipal User user) {
        ShareLinkDTO link = links.get(codec.decode(id), user);
        return link == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(link);
    }

    @PutMapping("/project/{id}/share-link")
    public ShareLinkDTO set(@PathVariable String id, @Valid @RequestBody ShareLinkRequest request, @AuthenticationPrincipal User user) {
        return links.set(codec.decode(id), ProjectRole.valueOf(request.role()), user);
    }

    @DeleteMapping("/project/{id}/share-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id, @AuthenticationPrincipal User user) {
        links.clear(codec.decode(id), user);
    }

    @PostMapping("/share-links/accept")
    public Map<String, String> accept(@Valid @RequestBody AcceptShareLinkRequest request, @AuthenticationPrincipal User user) {
        return Map.of("projectId", links.accept(request.token(), user));
    }
}
