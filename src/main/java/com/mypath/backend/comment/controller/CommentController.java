package com.mypath.backend.comment.controller;

import com.mypath.backend.comment.dto.CommentDTO;
import com.mypath.backend.comment.dto.CommentRequestDTO;
import com.mypath.backend.comment.service.CommentService;
import com.mypath.backend.common.ProjectIdCodec;
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
    private final ProjectIdCodec projectIdCodec;

    public CommentController(CommentService commentService, ModerationService moderationService, ProjectIdCodec projectIdCodec) {
        this.commentService = commentService;
        this.moderationService = moderationService;
        this.projectIdCodec = projectIdCodec;
    }

    @GetMapping("/api/public/project/{projectId}/comments")
    public ResponseEntity<List<CommentDTO>> getForProject(@PathVariable String projectId,
                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.getForProject(projectIdCodec.decode(projectId), user));
    }

    @PostMapping("/api/project/{projectId}/comments")
    public ResponseEntity<CommentDTO> create(@PathVariable String projectId,
                                               @Valid @RequestBody CommentRequestDTO request,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.create(projectIdCodec.decode(projectId), request, user));
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
