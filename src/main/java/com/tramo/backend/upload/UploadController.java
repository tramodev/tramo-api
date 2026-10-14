// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import com.tramo.backend.upload.dto.UploadPresignRequestDTO;
import com.tramo.backend.upload.dto.UploadPresignResponseDTO;
import com.tramo.backend.upload.dto.UploadCompleteResponseDTO;
import com.tramo.backend.upload.service.PublicUploadService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final PublicUploadService uploads;

    public UploadController(PublicUploadService uploads) { this.uploads = uploads; }

    @PostMapping("/presign")
    public ResponseEntity<UploadPresignResponseDTO> presign(@Valid @RequestBody UploadPresignRequestDTO request,
                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(uploads.presign(request, user));
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<UploadCompleteResponseDTO> complete(@PathVariable UUID uploadId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(uploads.complete(uploadId, user));
    }
}
