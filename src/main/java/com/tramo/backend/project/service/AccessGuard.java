package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class AccessGuard {
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PrivacyPolicy privacyPolicy;

    public AccessGuard(ProjectRepository projectRepository, UserRepository userRepository, PrivacyPolicy privacyPolicy) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.privacyPolicy = privacyPolicy;
    }

    public Project getOwnedProject(Long id, User requester) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Not allowed to access this project");
        }
        return project;
    }

    public void assertViewable(Project project, User requester) {
        privacyPolicy.assertProjectViewable(project, requester);
    }

    public User publicProfileTarget(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!privacyPolicy.isProfileViewable(target, requester)) {
            throw new ResourceNotFoundException("User not found");
        }
        return target;
    }
}
