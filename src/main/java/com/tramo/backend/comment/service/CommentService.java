package com.tramo.backend.comment.service;

import com.tramo.backend.comment.dto.CommentDTO;
import com.tramo.backend.comment.dto.CommentRequestDTO;
import com.tramo.backend.comment.entity.Comment;
import com.tramo.backend.project.dto.PageResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class CommentService {
    private final CommentRepository commentRepository;
    private final ProjectRepository projectRepository;
    private final NotificationService notificationService;
    private final BlockedUserRepository blockedUserRepository;
    private final PrivacyPolicy privacyPolicy;

    public CommentService(CommentRepository commentRepository, ProjectRepository projectRepository,
                           NotificationService notificationService, BlockedUserRepository blockedUserRepository,
                           PrivacyPolicy privacyPolicy) {
        this.commentRepository = commentRepository;
        this.projectRepository = projectRepository;
        this.notificationService = notificationService;
        this.blockedUserRepository = blockedUserRepository;
        this.privacyPolicy = privacyPolicy;
    }

    @Transactional
    public CommentDTO create(Long projectId, CommentRequestDTO request, User author) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(project, author);
        if (blockedUserRepository.existsEitherDirection(author.getId(), project.getOwner().getId())) {
            throw new AccessDeniedException("Cannot comment on this project");
        }
        if (!privacyPolicy.canComment(project.getOwner(), author)) {
            throw new AccessDeniedException("Comments are limited on this project");
        }

        Comment parent = null;
        if (request.getParentId() != null) {
            parent = commentRepository.findById(request.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent comment not found"));
            if (!parent.getProject().getId().equals(projectId)) {
                throw new IllegalArgumentException("Parent comment belongs to a different project");
            }
        }

        Comment comment = new Comment();
        comment.setProject(project);
        comment.setAuthor(author);
        comment.setParent(parent);
        comment.setContent(request.getContent().trim());
        comment.setCreatedDate(new Date());
        comment = commentRepository.save(comment);

        if (!project.getOwner().getId().equals(author.getId())) {
            notificationService.recordEvent(project.getOwner(), "COMMENT", project, author);
        }

        return toDto(comment, author);
    }

    public PageResponseDTO<CommentDTO> getForProject(Long projectId, User requester, int page, int size) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        boolean isOwner = requester != null && project.getOwner().getId().equals(requester.getId());
        if (!isOwner) {
            assertViewable(project, requester);
        }
        Page<Comment> roots = commentRepository.findRootsByProjectId(projectId, PageRequest.of(page, size));
        List<Long> rootIds = roots.getContent().stream().map(Comment::getId).toList();
        List<Comment> replies = rootIds.isEmpty() ? List.of() : commentRepository.findRepliesByParentIdIn(rootIds);

        Set<Long> blockRelated = privacyPolicy.blockRelatedIds(requester);
        List<CommentDTO> thread = new ArrayList<>();
        for (Comment root : roots.getContent()) {
            if (hiddenAuthor(root, blockRelated)) continue;
            thread.add(toDto(root, requester));
        }
        for (Comment reply : replies) {
            if (hiddenAuthor(reply, blockRelated)) continue;
            thread.add(toDto(reply, requester));
        }
        return new PageResponseDTO<>(thread, roots.hasNext());
    }

    @Transactional
    public void delete(Long commentId, User requester) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));
        boolean isAuthor = comment.getAuthor() != null && comment.getAuthor().getId().equals(requester.getId());
        boolean isProjectOwner = comment.getProject().getOwner().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == Role.ADMIN;
        if (!isAuthor && !isProjectOwner && !isAdmin) {
            throw new AccessDeniedException("Not allowed to delete this comment");
        }
        comment.setDeleted(true);
        comment.setContent(null);
        commentRepository.save(comment);
    }

    private void assertViewable(Project project, User requester) {
        privacyPolicy.assertProjectViewable(project, requester);
    }

    private boolean hiddenAuthor(Comment comment, Set<Long> blockRelated) {
        return comment.getAuthor() != null && blockRelated.contains(comment.getAuthor().getId());
    }

    private CommentDTO toDto(Comment comment, User requester) {
        boolean isAuthor = requester != null && comment.getAuthor() != null
                && comment.getAuthor().getId().equals(requester.getId());
        boolean isProjectOwner = requester != null
                && comment.getProject().getOwner().getId().equals(requester.getId());
        boolean isAdmin = requester != null && requester.getRole() == Role.ADMIN;
        return new CommentDTO(
                comment.getId(),
                comment.isDeleted() ? null : comment.getContent(),
                comment.isDeleted(),
                comment.getAuthor() != null ? comment.getAuthor().getUsername() : null,
                comment.getAuthor() != null ? comment.getAuthor().getImageUrl() : null,
                comment.getAuthor() != null ? comment.getAuthor().getSelectedBadge() : null,
                comment.getParent() != null ? comment.getParent().getId() : null,
                comment.getCreatedDate(),
                !comment.isDeleted() && (isAuthor || isProjectOwner || isAdmin)
        );
    }
}
