// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.service;

import com.tramo.backend.collab.dto.CollabDocumentDTO;
import com.tramo.backend.collab.dto.CollabSessionDTO;
import com.tramo.backend.collab.dto.CollabStoreRequest;
import com.tramo.backend.collab.entity.ItemYdoc;
import com.tramo.backend.collab.repository.ItemYdocRepository;
import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.exception.ExtractionConflictException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.service.ProjectVersionCapture;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CollabService {
    private final ItemService itemService;
    private final ItemRepository items;
    private final ItemYdocRepository ydocs;
    private final UserRepository users;
    private final ProjectVersionCapture history;
    private final byte[] secret;

    public CollabService(ItemService itemService, ItemRepository items, ItemYdocRepository ydocs, UserRepository users,
                         ProjectVersionCapture history, @Value("${app.collab.internal-secret:}") String secret) {
        this.itemService = itemService;
        this.items = items;
        this.ydocs = ydocs;
        this.users = users;
        this.history = history;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void assertInternal(String supplied) {
        if (secret.length == 0 || supplied == null
                || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException("Invalid collaboration secret");
    }

    @Transactional
    public CollabDocumentDTO load(Long itemId) {
        Item item = items.findByIdWithContent(itemId).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        int epoch = epoch(item);
        byte[] state = ydocs.findById(itemId).filter(ydoc -> ydoc.getExtractionEpoch() == epoch).map(ItemYdoc::getState).orElse(null);
        return new CollabDocumentDTO(state, content(item), epoch);
    }

    @Transactional
    public void store(Long itemId, CollabStoreRequest request) {
        ContentLimits.assertNote(request.content());
        User author = users.findById(request.authorId()).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        Project project = history.lockItem(itemId, author);
        items.lockById(itemId).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        if (!itemService.roleOf(itemId, author).atLeast(ProjectRole.EDITOR))
            throw new AccessDeniedException("Insufficient project role");
        Item item = items.findByIdWithContent(itemId).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        int epoch = epoch(item);
        if (request.extractionEpoch() != epoch)
            throw new ExtractionConflictException("This note was restored in another session.");
        ItemYdoc ydoc = ydocs.findById(itemId).filter(existing -> existing.getExtractionEpoch() == epoch).orElse(null);
        if (request.bootstrapHash() == null ? ydoc == null : ydoc != null || !request.bootstrapHash().equals(hash(content(item))))
            throw new ExtractionConflictException("This note changed while it was opening.");
        itemService.applyContent(item, project, request.content(), null);
        if (ydoc == null) ydoc = ydocs.findById(itemId).orElseGet(() -> new ItemYdoc(itemId));
        ydoc.setState(request.state());
        ydoc.setExtractionEpoch(epoch);
        ydoc.setUpdatedAt(new Date());
        ydocs.save(ydoc);
    }

    @Transactional
    public List<Integer> check(List<CollabSessionDTO> sessions) {
        Map<Long, User> byId = users.findAllById(sessions.stream().map(CollabSessionDTO::userId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, ProjectRole> roles = new HashMap<>();
        List<Integer> invalid = new ArrayList<>();
        for (int index = 0; index < sessions.size(); index++) {
            CollabSessionDTO session = sessions.get(index);
            User user = byId.get(session.userId());
            ProjectRole role = user == null || !user.isEnabled() || !user.isAccountNonLocked()
                    || !Objects.equals(Objects.toString(user.getSessionVersion(), null), session.sv())
                    ? null : roles.computeIfAbsent(session.userId() + ":" + session.itemId(), key -> roleOrNull(session.itemId(), user));
            if (role == null || ("edit".equals(session.perm()) && !role.atLeast(ProjectRole.EDITOR))) invalid.add(index);
        }
        return invalid;
    }

    private ProjectRole roleOrNull(Long itemId, User user) {
        try {
            return itemService.roleOf(itemId, user);
        } catch (ResourceNotFoundException gone) {
            return null;
        }
    }

    private static int epoch(Item item) {
        return item.getContent() == null ? 0 : item.getContent().getExtractionEpoch();
    }

    private static String content(Item item) {
        return item.getContent() == null || item.getContent().getContent() == null ? "" : item.getContent().getContent();
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
