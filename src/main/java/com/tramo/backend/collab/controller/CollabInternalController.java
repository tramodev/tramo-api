// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.controller;

import com.tramo.backend.collab.dto.CollabDocumentDTO;
import com.tramo.backend.collab.dto.CollabSessionDTO;
import com.tramo.backend.collab.dto.CollabStoreRequest;
import com.tramo.backend.collab.service.CollabService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/collab")
public class CollabInternalController {
    private final CollabService collab;

    public CollabInternalController(CollabService collab) {
        this.collab = collab;
    }

    @GetMapping("/items/{id}")
    public CollabDocumentDTO load(@PathVariable Long id, @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return collab.load(id);
    }

    @PutMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void store(@PathVariable Long id, @Valid @RequestBody CollabStoreRequest request,
                      @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        collab.store(id, request);
    }

    @PostMapping("/check")
    public List<Integer> check(@RequestBody List<@Valid CollabSessionDTO> sessions,
                               @RequestHeader(value = "X-Collab-Secret", required = false) String secret) {
        collab.assertInternal(secret);
        return collab.check(sessions);
    }
}
