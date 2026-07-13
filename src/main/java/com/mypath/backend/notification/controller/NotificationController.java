package com.mypath.backend.notification.controller;

import com.mypath.backend.notification.dto.NotificationDTO;
import com.mypath.backend.notification.dto.UnreadCountDTO;
import com.mypath.backend.notification.service.NotificationService;
import com.mypath.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Outside /api/public/** — SecurityConfiguration's anyRequest().authenticated()
// gates this, matching FollowController's reasoning.
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationDTO>> getNotifications(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notificationService.getNotifications(user));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountDTO> getUnreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new UnreadCountDTO(notificationService.getUnreadCount(user)));
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User user) {
        notificationService.markAllRead(user);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id, @AuthenticationPrincipal User user) {
        notificationService.deleteNotification(user, id);
        return ResponseEntity.ok().build();
    }
}
