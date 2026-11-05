// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ProjectMemberDTO;
import com.tramo.backend.project.dto.ProjectMemberRequest;
import com.tramo.backend.project.dto.ProjectMemberRoleRequest;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.service.ProjectMemberService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/project/{id}")
public class ProjectMemberController {
    private final ProjectMemberService members;
    private final ProjectIdCodec codec;

    public ProjectMemberController(ProjectMemberService members, ProjectIdCodec codec) {
        this.members = members; this.codec = codec;
    }

    @GetMapping("/members")
    public List<ProjectMemberDTO> list(@PathVariable String id, @AuthenticationPrincipal User user) {
        return members.list(codec.decode(id), user);
    }

    @PostMapping("/members")
    public ProjectMemberDTO add(@PathVariable String id, @Valid @RequestBody ProjectMemberRequest request, @AuthenticationPrincipal User user) {
        return members.add(codec.decode(id), request.username(), ProjectRole.valueOf(request.role()), user);
    }

    @PatchMapping("/members/{username}")
    public ProjectMemberDTO changeRole(@PathVariable String id, @PathVariable String username,
            @Valid @RequestBody ProjectMemberRoleRequest request, @AuthenticationPrincipal User user) {
        return members.changeRole(codec.decode(id), username, ProjectRole.valueOf(request.role()), user);
    }

    @DeleteMapping("/members/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id, @PathVariable String username, @AuthenticationPrincipal User user) {
        members.remove(codec.decode(id), username, user);
    }

    @DeleteMapping("/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String id, @AuthenticationPrincipal User user) {
        members.leave(codec.decode(id), user);
    }
}
