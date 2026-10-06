// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.tag.controller;

import com.tramo.backend.tag.dto.TagDTO;
import com.tramo.backend.tag.service.TagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping("/api/tags/autocomplete")
    public ResponseEntity<List<TagDTO>> autocomplete(@RequestParam(defaultValue = "") String q,
                                                       @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(tagService.autocomplete(q, Math.min(limit, 50)));
    }
}
