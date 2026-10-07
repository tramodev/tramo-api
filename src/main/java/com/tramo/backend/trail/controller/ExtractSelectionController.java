package com.tramo.backend.trail.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.trail.dto.*;
import com.tramo.backend.trail.service.ExtractSelectionService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/project/{projectId}/item/{sourceId}/extract")
public class ExtractSelectionController {
    private final ExtractSelectionService extracts;
    private final ProjectIdCodec codec;
    public ExtractSelectionController(ExtractSelectionService extracts, ProjectIdCodec codec) { this.extracts = extracts; this.codec = codec; }
    @GetMapping
    public java.util.Map<String, Integer> usage(@PathVariable String projectId, @PathVariable Long sourceId, @AuthenticationPrincipal User user) {
        return java.util.Map.of("trailCount", extracts.sharedCount(codec.decode(projectId), sourceId, user));
    }
    @PostMapping
    public ExtractSelectionResponse extract(@PathVariable String projectId, @PathVariable Long sourceId,
            @Valid @RequestBody ExtractSelectionRequest request, @AuthenticationPrincipal User user) {
        return extracts.extract(codec.decode(projectId), sourceId, request, user);
    }
}
