package com.tramo.backend.notification.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.notification.dto.NotificationDTO;
import com.tramo.backend.notification.dto.UnreadCountDTO;
import com.tramo.backend.notification.entity.Notification;
import com.tramo.backend.project.dto.PageResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.UserPreferencesService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationService {
    private static final int READ_RETENTION_DAYS = 90;
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final ProjectIdCodec projectIdCodec;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final long sseTimeoutMs;

    public NotificationService(NotificationRepository notificationRepository, ProjectIdCodec projectIdCodec,
                                @Value("${app.notifications.sse-timeout-ms:1800000}") long sseTimeoutMs) {
        this.notificationRepository = notificationRepository;
        this.projectIdCodec = projectIdCodec;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeOldReadNotifications() {
        Date cutoff = new Date(System.currentTimeMillis() - READ_RETENTION_DAYS * 24L * 60 * 60 * 1000);
        int deleted = notificationRepository.deleteReadOlderThan(cutoff);
        if (deleted > 0) {
            log.info("purgeOldReadNotifications deleted {} read notifications older than {} days", deleted, READ_RETENTION_DAYS);
        }
    }

    @Transactional
    public void deleteAllForProject(Long projectId) {
        notificationRepository.deleteByProjectId(projectId);
    }

    @Transactional
    public void deleteAllForRecipient(Long recipientId) {
        notificationRepository.deleteByRecipientId(recipientId);
    }

    @Transactional
    public void clearLatestActorReferences(Long actorId) {
        notificationRepository.clearLatestActorReferences(actorId);
    }

    public SseEmitter subscribe(User user) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        CopyOnWriteArrayList<SseEmitter> userEmitters =
                emitters.computeIfAbsent(user.getId(), id -> new CopyOnWriteArrayList<>());
        userEmitters.add(emitter);

        Runnable cleanup = () -> userEmitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("unread-count").data(new UnreadCountDTO(getUnreadCount(user))));
        } catch (IOException e) {
            cleanup.run();
        }

        return emitter;
    }

    private void publishUnreadCount(User recipient) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendUnreadCount(recipient);
                }
            });
            return;
        }
        sendUnreadCount(recipient);
    }

    private void sendUnreadCount(User recipient) {
        CopyOnWriteArrayList<SseEmitter> userEmitters = emitters.get(recipient.getId());
        if (userEmitters == null || userEmitters.isEmpty()) return;

        UnreadCountDTO payload = new UnreadCountDTO(getUnreadCount(recipient));
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().name("unread-count").data(payload));
            } catch (IOException e) {
                emitter.complete();
                userEmitters.remove(emitter);
            }
        }
    }

    @Transactional
    public void recordEvent(User recipient, String type, Project project, User actor) {
        if (recipient.getId().equals(actor.getId())) return;
        if (muted(recipient, type)) return;

        Date now = new Date();
        int merged = project != null
                ? notificationRepository.incrementForProject(recipient.getId(), type, project.getId(), actor, now)
                : notificationRepository.incrementWithoutProject(recipient.getId(), type, actor, now);

        if (merged == 0) {
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
        publishUnreadCount(recipient);
    }

    @Transactional
    public void recordEventForAll(List<User> recipients, String type, Project project, User actor) {
        List<User> targets = recipients.stream()
                .filter(recipient -> !recipient.getId().equals(actor.getId()))
                .filter(recipient -> !muted(recipient, type))
                .toList();
        if (targets.isEmpty()) return;

        Date now = new Date();
        List<Long> targetIds = targets.stream().map(User::getId).toList();
        Set<Long> merged = Set.copyOf(
                notificationRepository.findUnreadRecipientIds(targetIds, type, project.getId()));
        if (!merged.isEmpty()) {
            notificationRepository.incrementForProjectIn(merged, type, project.getId(), actor, now);
        }

        List<Notification> fresh = new ArrayList<>();
        for (User recipient : targets) {
            if (merged.contains(recipient.getId())) continue;
            Notification notification = new Notification();
            notification.setRecipient(recipient);
            notification.setType(type);
            notification.setProject(project);
            notification.setLatestActor(actor);
            notification.setCount(1);
            notification.setCreatedDate(now);
            notification.setUpdatedDate(now);
            fresh.add(notification);
        }
        if (!fresh.isEmpty()) {
            notificationRepository.saveAll(fresh);
        }
        targets.forEach(this::publishUnreadCount);
    }

    @Transactional
    public void recordBadge(User recipient, String badgeCode, String badgeName) {
        if (muted(recipient, NotificationTypes.BADGE)) return;

        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType(NotificationTypes.BADGE);
        notification.setBadgeCode(badgeCode);
        notification.setBadgeName(badgeName);
        Date now = new Date();
        notification.setCreatedDate(now);
        notification.setUpdatedDate(now);
        notificationRepository.save(notification);
        publishUnreadCount(recipient);
    }

    @Transactional
    public void recordFeatured(User recipient, Project project) {
        if (muted(recipient, NotificationTypes.FEATURED)) return;

        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType(NotificationTypes.FEATURED);
        notification.setProject(project);
        Date now = new Date();
        notification.setCreatedDate(now);
        notification.setUpdatedDate(now);
        notificationRepository.save(notification);
        publishUnreadCount(recipient);
    }

    private boolean muted(User recipient, String type) {
        if (Boolean.FALSE.equals(recipient.getNotificationsEnabled())) {
            return true;
        }
        return UserPreferencesService.decodeMutedTypes(recipient.getMutedNotificationTypes()).contains(type);
    }

    public PageResponseDTO<NotificationDTO> getNotifications(User user, int page, int size) {
        Page<Notification> result = notificationRepository.findByRecipientIdOrderByUpdatedDateDesc(
                user.getId(), PageRequest.of(page, size));
        List<NotificationDTO> items = result.getContent().stream()
                .map(n -> new NotificationDTO(
                        n.getId(),
                        n.getType(),
                        n.getProject() != null ? projectIdCodec.encode(n.getProject().getId()) : null,
                        n.getProject() != null ? n.getProject().getTitle() : null,
                        n.getBadgeCode(),
                        n.getBadgeName(),
                        n.getLatestActor() != null ? n.getLatestActor().getUsername() : null,
                        n.getCount(),
                        n.isRead(),
                        n.getUpdatedDate()
                ))
                .toList();
        return new PageResponseDTO<>(items, result.hasNext());
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
