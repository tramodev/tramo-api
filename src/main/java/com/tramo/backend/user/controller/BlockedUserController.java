package com.tramo.backend.user.controller;

import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.user.dto.BlockResponseDTO;
import com.tramo.backend.user.dto.BlockedUserDTO;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.BlockedUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class BlockedUserController {
    private final BlockedUserService blockedUserService;

    public BlockedUserController(BlockedUserService blockedUserService) {
        this.blockedUserService = blockedUserService;
    }

    @PostMapping("/{username}/block")
    public ResponseEntity<BlockResponseDTO> toggleBlock(@PathVariable String username,
                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(blockedUserService.toggleBlock(user, username));
    }

    @GetMapping("/blocked")
    public ResponseEntity<PageResponseDTO<BlockedUserDTO>> listBlocked(@RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "20") int size,
                                                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(blockedUserService.list(user, page, size));
    }
}
