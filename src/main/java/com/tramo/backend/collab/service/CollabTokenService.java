// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.service;

import com.tramo.backend.collab.dto.CollabTokenDTO;
import com.tramo.backend.project.entity.ProjectRole;
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
    private final SecretKey key;

    public CollabTokenService(ItemService itemService, @Value("${app.collab.token-secret:}") String secret) {
        this.itemService = itemService;
        this.key = secret.isBlank() ? null : Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public CollabTokenDTO issue(Long itemId, User user) {
        if (key == null) throw new IllegalStateException("Collaboration is not configured");
        ProjectRole role = itemService.roleOf(itemId, user);
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .issuer("tramo-api")
                .audience().add("tramo-collab").and()
                .subject(user.getId().toString())
                .claim("item", itemId.toString())
                .claim("perm", role.atLeast(ProjectRole.EDITOR) ? "edit" : "view")
                .claim("sv", Objects.toString(user.getSessionVersion(), null))
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MS))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new CollabTokenDTO(token);
    }
}
