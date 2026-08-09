package com.tramo.backend.user.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.user.dto.UpdatePreferencesRequestDTO;
import com.tramo.backend.user.dto.UserPreferencesDTO;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class UserPreferencesService {
    private final UserRepository userRepository;

    public UserPreferencesService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserPreferencesDTO getPreferences(User principal) {
        return toDto(fresh(principal));
    }

    @Transactional
    public UserPreferencesDTO updatePreferences(User principal, UpdatePreferencesRequestDTO request) {
        User user = fresh(principal);
        if (request.getProfileVisibility() != null) {
            user.setVisibility("public".equals(request.getProfileVisibility()));
        }
        if (request.getEmailDigestFrequency() != null) {
            user.setEmailDigestFrequency(request.getEmailDigestFrequency());
        }
        if (request.getShowUpvotes() != null) {
            user.setShowUpvotes(request.getShowUpvotes());
        }
        if (request.getShowAge() != null) {
            user.setShowAge(request.getShowAge());
        }
        if (request.getAllowForks() != null) {
            user.setAllowForks(request.getAllowForks());
        }
        if (request.getCommentsPolicy() != null) {
            user.setCommentsPolicy(request.getCommentsPolicy());
        }
        if (request.getEditorTourSeen() != null) {
            user.setEditorTourSeen(request.getEditorTourSeen());
        }
        if (request.getNotificationsEnabled() != null) {
            user.setNotificationsEnabled(request.getNotificationsEnabled());
        }
        if (request.getMutedNotificationTypes() != null) {
            user.setMutedNotificationTypes(encodeMutedTypes(request.getMutedNotificationTypes()));
        }
        userRepository.save(user);
        return toDto(user);
    }

    private String encodeMutedTypes(List<String> types) {
        List<String> normalized = types.stream()
                .filter(Objects::nonNull)
                .map(type -> type.trim().toUpperCase())
                .distinct()
                .toList();
        for (String type : normalized) {
            if (!NotificationTypes.isKnown(type)) {
                throw new IllegalArgumentException("Unknown notification type: " + type);
            }
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    public static List<String> decodeMutedTypes(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .toList();
    }

    private User fresh(User principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserPreferencesDTO toDto(User user) {
        return new UserPreferencesDTO(
                Boolean.FALSE.equals(user.getVisibility()) ? "private" : "public",
                user.getEmailDigestFrequency() != null ? user.getEmailDigestFrequency() : "weekly",
                user.getShowUpvotes() == null || user.getShowUpvotes(),
                user.getShowAge() == null || user.getShowAge(),
                user.getAllowForks() == null || user.getAllowForks(),
                user.getCommentsPolicy() != null ? user.getCommentsPolicy() : "everyone",
                Boolean.TRUE.equals(user.getEditorTourSeen()),
                !Boolean.FALSE.equals(user.getNotificationsEnabled()),
                decodeMutedTypes(user.getMutedNotificationTypes())
        );
    }
}
