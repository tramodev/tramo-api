// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ProjectAccessRequestDTO;
import com.tramo.backend.project.dto.ProjectMemberDTO;
import com.tramo.backend.project.dto.ProjectMemberRoleRequest;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.service.ProjectAccessRequestService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/project/{id}")
public class ProjectAccessRequestController {
    private final ProjectAccessRequestService requests;
    private final ProjectIdCodec codec;

    public ProjectAccessRequestController(ProjectAccessRequestService requests, ProjectIdCodec codec) {
        this.requests = requests; this.codec = codec;
    }

    @PostMapping("/access-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@PathVariable String id, @AuthenticationPrincipal User user) {
        codec.tryDecode(id).ifPresent(projectId -> requests.request(projectId, user));
    }

    @GetMapping("/access-requests")
    public List<ProjectAccessRequestDTO> list(@PathVariable String id, @AuthenticationPrincipal User user) {
        return requests.list(codec.decode(id), user);
    }

    @PostMapping("/access-requests/{username}/approve")
    public ProjectMemberDTO approve(@PathVariable String id, @PathVariable String username,
            @Valid @RequestBody ProjectMemberRoleRequest request, @AuthenticationPrincipal User user) {
        return requests.approve(codec.decode(id), username, ProjectRole.valueOf(request.role()), user);
    }

    @DeleteMapping("/access-requests/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(@PathVariable String id, @PathVariable String username, @AuthenticationPrincipal User user) {
        requests.deny(codec.decode(id), username, user);
    }
}
