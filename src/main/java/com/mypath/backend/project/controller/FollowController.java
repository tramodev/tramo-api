package com.mypath.backend.project.controller;

import com.mypath.backend.project.dto.FollowResponseDTO;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class FollowController {
    private final ProjectService projectService;

    public FollowController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/{username}/follow")
    public ResponseEntity<FollowResponseDTO> toggleFollow(@PathVariable String username,
                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.toggleFollow(username, user));
    }
}
