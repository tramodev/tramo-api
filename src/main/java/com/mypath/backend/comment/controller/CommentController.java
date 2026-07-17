package com.mypath.backend.comment.controller;

import com.mypath.backend.comment.dto.CommentDTO;
import com.mypath.backend.comment.dto.CommentRequestDTO;
import com.mypath.backend.comment.service.CommentService;
import com.mypath.backend.moderation.dto.ReportRequestDTO;
import com.mypath.backend.moderation.service.ModerationService;
import com.mypath.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CommentController {
    private final CommentService commentService;
    private final ModerationService moderationService;

    public CommentController(CommentService commentService, ModerationService moderationService) {
        this.commentService = commentService;
        this.moderationService = moderationService;
    }

    @GetMapping("/api/public/project/{projectId}/comments")
    public ResponseEntity<List<CommentDTO>> getForProject(@PathVariable Long projectId,
                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.getForProject(projectId, user));
    }

    @PostMapping("/api/project/{projectId}/comments")
    public ResponseEntity<CommentDTO> create(@PathVariable Long projectId,
                                               @Valid @RequestBody CommentRequestDTO request,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.create(projectId, request, user));
    }

    @DeleteMapping("/api/comment/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        commentService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/comment/{id}/report")
    public ResponseEntity<Void> report(@PathVariable Long id, @Valid @RequestBody ReportRequestDTO request,
                                         @AuthenticationPrincipal User user) {
        moderationService.submitCommentReport(id, user, request.getReason());
        return ResponseEntity.ok().build();
    }
}
