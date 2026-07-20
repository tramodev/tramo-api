package com.tramo.backend.user.service;

import com.tramo.backend.user.dto.UpdatePreferencesRequestDTO;
import com.tramo.backend.user.dto.UserPreferencesDTO;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class UserPreferencesService {
    private final UserRepository userRepository;

    public UserPreferencesService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserPreferencesDTO getPreferences(User user) {
        return toDto(user);
    }

    @Transactional
    public UserPreferencesDTO updatePreferences(User user, UpdatePreferencesRequestDTO request) {
        if (request.getProfileVisibility() != null) {
            user.setVisibility("public".equals(request.getProfileVisibility()));
        }
        if (request.getEmailDigestFrequency() != null) {
            user.setEmailDigestFrequency(request.getEmailDigestFrequency());
        }
        if (request.getShowUpvotes() != null) {
            user.setShowUpvotes(request.getShowUpvotes());
        }
        if (request.getAllowForks() != null) {
            user.setAllowForks(request.getAllowForks());
        }
        if (request.getCommentsPolicy() != null) {
            user.setCommentsPolicy(request.getCommentsPolicy());
        }
        userRepository.save(user);
        return toDto(user);
    }

    private UserPreferencesDTO toDto(User user) {
        return new UserPreferencesDTO(
                Boolean.FALSE.equals(user.getVisibility()) ? "private" : "public",
                user.getEmailDigestFrequency() != null ? user.getEmailDigestFrequency() : "weekly",
                user.getShowUpvotes() == null || user.getShowUpvotes(),
                user.getAllowForks() == null || user.getAllowForks(),
                user.getCommentsPolicy() != null ? user.getCommentsPolicy() : "everyone"
        );
    }
}
