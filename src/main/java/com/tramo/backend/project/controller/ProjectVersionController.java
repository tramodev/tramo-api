package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.*;
import com.tramo.backend.project.service.ProjectVersionService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/project/{id}/history")
public class ProjectVersionController {
    private final ProjectVersionService versions;
    private final ProjectIdCodec codec;

    public ProjectVersionController(ProjectVersionService versions, ProjectIdCodec codec) {
        this.versions = versions; this.codec = codec;
    }

    @GetMapping
    public ProjectVersionService.History list(@PathVariable String id, @RequestParam(required = false) Long beforeId, @AuthenticationPrincipal User user) {
        return versions.list(codec.decode(id), beforeId, user);
    }

    @GetMapping("/{versionId}")
    public ProjectVersionService.Detail detail(@PathVariable String id, @PathVariable Long versionId, @AuthenticationPrincipal User user) {
        return versions.detail(codec.decode(id), versionId, user);
    }

    @PostMapping
    public ProjectVersionSummaryDTO create(@PathVariable String id, @Valid @RequestBody CreateProjectVersionRequest request, @AuthenticationPrincipal User user) {
        return versions.create(codec.decode(id), request.name(), user);
    }

    @PostMapping("/{versionId}/restore")
    public Map<String, Long> restore(@PathVariable String id, @PathVariable Long versionId,
            @RequestHeader(value = "X-Project-Epoch", required = false) String epoch, @AuthenticationPrincipal User user) {
        if (epoch == null || epoch.isBlank()) throw new com.tramo.backend.exception.PreconditionRequiredException("Reload the project before restoring; X-Project-Epoch is required.");
        return Map.of("restoreEpoch", versions.restore(codec.decode(id), versionId, user));
    }
}
