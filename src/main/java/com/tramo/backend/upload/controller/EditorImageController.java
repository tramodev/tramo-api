// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.service.ProjectShareLinkService;
import com.tramo.backend.upload.dto.*;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class EditorImageController {
    private final EditorImageService images;
    private final ProjectShareLinkService links;
    private final ProjectIdCodec codec;

    public EditorImageController(EditorImageService images, ProjectShareLinkService links, ProjectIdCodec codec) {
        this.images = images; this.links = links; this.codec = codec;
    }

    @PostMapping("/uploads/editor-images/presign")
    public ResponseEntity<EditorImagePresignResponse> presign(@Valid @RequestBody EditorImagePresignRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.presign(request, user));
    }

    @PostMapping("/uploads/editor-images/{imageId}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID imageId, @AuthenticationPrincipal User user) {
        images.complete(imageId, user);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/project/{projectId}/editor-images/resolve")
    public ResponseEntity<EditorImageResolveResponse> resolve(@PathVariable String projectId,
            @Valid @RequestBody EditorImageResolveRequest request, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.resolve(codec.decode(projectId), request, user, false));
    }

    @PostMapping("/public/project/{projectId}/editor-images/resolve")
    public ResponseEntity<EditorImageResolveResponse> resolvePublic(@PathVariable String projectId,
            @Valid @RequestBody EditorImageResolveRequest request, @AuthenticationPrincipal User user,
            @RequestHeader(value = "X-Share-Token", required = false) String shareToken) {
        Long id = codec.decode(projectId);
        if (shareToken == null) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.resolve(id, request, user, true));
        if (!links.viewable(shareToken, user).getId().equals(id)) throw new ResourceNotFoundException("Image not found");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.resolveShared(id, request));
    }
}
