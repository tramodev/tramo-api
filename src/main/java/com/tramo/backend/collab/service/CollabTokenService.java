// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.service;

import com.tramo.backend.collab.dto.CollabTokenDTO;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Objects;

@Service
public class CollabTokenService {
    private static final long TTL_MS = 120_000;

    private final ItemService itemService;
    private final ProjectRepository projects;
    private final AccessGuard accessGuard;
    private final SecretKey key;

    public CollabTokenService(ItemService itemService, ProjectRepository projects, AccessGuard accessGuard,
                              @Value("${app.collab.token-secret:}") String secret) {
        this.itemService = itemService;
        this.projects = projects;
        this.accessGuard = accessGuard;
        this.key = secret.isBlank() ? null : Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public CollabTokenDTO issue(Long itemId, User user) {
        if (key == null) throw new IllegalStateException("Collaboration is not configured");
        return sign("item", itemId, itemService.roleOf(itemId, user), user);
    }

    public CollabTokenDTO issueProject(Long projectId, User user) {
        if (key == null) throw new IllegalStateException("Collaboration is not configured");
        Project project = projects.findById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        ProjectRole role = accessGuard.roleOf(project, user);
        if (role == null) throw new ResourceNotFoundException("Project not found");
        return sign("project", projectId, role, user);
    }

    private CollabTokenDTO sign(String target, Long id, ProjectRole role, User user) {
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .issuer("tramo-api")
                .audience().add("tramo-collab").and()
                .subject(user.getId().toString())
                .claim(target, id.toString())
                .claim("perm", role.atLeast(ProjectRole.EDITOR) ? "edit" : "view")
                .claim("role", role.name())
                .claim("sv", Objects.toString(user.getSessionVersion(), null))
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MS))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new CollabTokenDTO(token);
    }
}
