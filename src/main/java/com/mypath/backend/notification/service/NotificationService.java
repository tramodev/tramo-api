package com.mypath.backend.notification.service;

import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.notification.dto.NotificationDTO;
import com.mypath.backend.notification.entity.Notification;
import com.mypath.backend.notification.repository.NotificationRepository;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {
    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void recordEvent(User recipient, String type, Project project, User actor) {
        if (recipient.getId().equals(actor.getId())) return;

        Optional<Notification> existing = project != null
                ? notificationRepository.findByRecipientIdAndTypeAndProjectIdAndReadFalse(recipient.getId(), type, project.getId())
                : notificationRepository.findByRecipientIdAndTypeAndProjectIsNullAndReadFalse(recipient.getId(), type);

        Date now = new Date();
        if (existing.isPresent()) {
            Notification notification = existing.get();
            notification.setCount(notification.getCount() + 1);
            notification.setLatestActor(actor);
            notification.setUpdatedDate(now);
        } else {
            Notification notification = new Notification();
            notification.setRecipient(recipient);
            notification.setType(type);
            notification.setProject(project);
            notification.setLatestActor(actor);
            notification.setCount(1);
            notification.setCreatedDate(now);
            notification.setUpdatedDate(now);
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public void recordBadge(User recipient, String badgeCode, String badgeName) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType("BADGE");
        notification.setBadgeCode(badgeCode);
        notification.setBadgeName(badgeName);
        Date now = new Date();
        notification.setCreatedDate(now);
        notification.setUpdatedDate(now);
        notificationRepository.save(notification);
    }

    @Transactional
    public void recordFeatured(User recipient, Project project) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType("FEATURED");
        notification.setProject(project);
        Date now = new Date();
        notification.setCreatedDate(now);
        notification.setUpdatedDate(now);
        notificationRepository.save(notification);
    }

    public List<NotificationDTO> getNotifications(User user) {
        return notificationRepository.findByRecipientIdOrderByUpdatedDateDesc(user.getId()).stream()
                .map(n -> new NotificationDTO(
                        n.getId(),
                        n.getType(),
                        n.getProject() != null ? n.getProject().getId() : null,
                        n.getProject() != null ? n.getProject().getTitle() : null,
                        n.getBadgeCode(),
                        n.getBadgeName(),
                        n.getLatestActor() != null ? n.getLatestActor().getUsername() : null,
                        n.getCount(),
                        n.isRead(),
                        n.getUpdatedDate()
                ))
                .toList();
    }

    public long getUnreadCount(User user) {
        return notificationRepository.countByRecipientIdAndReadFalse(user.getId());
    }

    @Transactional
    public void markAllRead(User user) {
        notificationRepository.markAllReadByRecipientId(user.getId());
    }

    @Transactional
    public void deleteNotification(User user, Long notificationId) {
        long deleted = notificationRepository.deleteByIdAndRecipientId(notificationId, user.getId());
        if (deleted == 0) {
            throw new ResourceNotFoundException("Notification not found");
        }
    }
}
