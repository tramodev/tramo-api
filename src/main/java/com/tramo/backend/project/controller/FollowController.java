package com.tramo.backend.project.controller;

import com.tramo.backend.project.dto.FollowResponseDTO;
import com.tramo.backend.project.service.FollowService;
import com.tramo.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class FollowController {
    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PostMapping("/{username}/follow")
    public ResponseEntity<FollowResponseDTO> toggleFollow(@PathVariable String username,
                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(followService.toggleFollow(username, user));
    }
}
