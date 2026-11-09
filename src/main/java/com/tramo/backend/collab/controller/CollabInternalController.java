// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.controller;

import com.tramo.backend.collab.dto.CollabDocumentDTO;
import com.tramo.backend.collab.dto.CollabSessionDTO;
import com.tramo.backend.collab.dto.CollabStoreRequest;
import com.tramo.backend.collab.service.CollabService;
import com.tramo.backend.project.dto.ProjectStructureDTO;
import com.tramo.backend.project.service.ProjectEditorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/collab")
public class CollabInternalController {
    private final CollabService collab;
    private final ProjectEditorService editor;

    public CollabInternalController(CollabService collab, ProjectEditorService editor) {
        this.collab = collab;
        this.editor = editor;
    }

    @GetMapping("/items/{id}")
    public CollabDocumentDTO load(@PathVariable Long id, @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return collab.load(id);
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<Map<String, String>> store(@PathVariable Long id, @Valid @RequestBody CollabStoreRequest request,
                                                     @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        String sanitized = collab.store(id, request);
        return sanitized == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(Map.of("content", sanitized));
    }

    @GetMapping("/projects/{id}/structure")
    public ProjectStructureDTO structure(@PathVariable Long id, @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return editor.structure(id);
    }

    @PostMapping("/projects/structure-versions")
    public Map<Long, Long> structureVersions(@RequestBody List<@NotNull Long> ids,
                                             @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return collab.structureVersions(ids);
    }

    @PostMapping("/check")
    public List<Integer> check(@RequestBody List<@Valid @NotNull CollabSessionDTO> sessions,
                               @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return collab.check(sessions);
    }
}
