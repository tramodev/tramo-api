package com.mypath.backend.comment.service;

import com.mypath.backend.comment.dto.CommentDTO;
import com.mypath.backend.comment.dto.CommentRequestDTO;
import com.mypath.backend.comment.entity.Comment;
import com.mypath.backend.comment.repository.CommentRepository;
import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.notification.service.NotificationService;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.repository.ProjectRepository;
import com.mypath.backend.user.Role;
import com.mypath.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class CommentService {
    private final CommentRepository commentRepository;
    private final ProjectRepository projectRepository;
    private final NotificationService notificationService;

    public CommentService(CommentRepository commentRepository, ProjectRepository projectRepository,
                           NotificationService notificationService) {
        this.commentRepository = commentRepository;
        this.projectRepository = projectRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public CommentDTO create(Long projectId, CommentRequestDTO request, User author) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(project);

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

    public List<CommentDTO> getForProject(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        boolean isOwner = requester != null && project.getOwner().getId().equals(requester.getId());
        if (!isOwner) {
            assertViewable(project);
        }
        return commentRepository.findByProjectIdOrderByCreatedDateAsc(projectId).stream()
                .map(c -> toDto(c, requester))
                .toList();
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

    private void assertViewable(Project project) {
        String visibility = project.getVisibility();
        if (!"unlisted".equals(visibility) && !"published".equals(visibility)) {
            throw new ResourceNotFoundException("Project not found");
        }
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
                comment.getParent() != null ? comment.getParent().getId() : null,
                comment.getCreatedDate(),
                !comment.isDeleted() && (isAuthor || isProjectOwner || isAdmin)
        );
    }
}
