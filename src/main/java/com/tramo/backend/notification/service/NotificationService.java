// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.notification.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.common.Pagination;
import com.tramo.backend.common.SafeLog;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.notification.dto.NotificationDTO;
import com.tramo.backend.notification.dto.UnreadCountDTO;
import com.tramo.backend.notification.entity.Notification;
import com.tramo.backend.project.dto.PageResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.UserPreferencesService;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationService {
    private static final int READ_RETENTION_DAYS = 90;
    private static final int MAX_CONNECTIONS_PER_USER = 5;
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final ProjectIdCodec projectIdCodec;
    private record Connection(SseEmitter emitter, UUID sessionVersion) {}

    private final Map<Long, CopyOnWriteArrayList<Connection>> emitters = new ConcurrentHashMap<>();
    private final long sseTimeoutMs;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, ProjectIdCodec projectIdCodec,
                                @Value("${app.notifications.sse-timeout-ms:1800000}") long sseTimeoutMs,
                                UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.projectIdCodec = projectIdCodec;
        this.sseTimeoutMs = sseTimeoutMs;
        this.userRepository = userRepository;
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
        Connection connection = new Connection(emitter, user.getSessionVersion());
        Runnable cleanup = () -> removeEmitter(user.getId(), emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        Connection evicted = null;
        synchronized (emitters) {
            var connections = emitters.get(user.getId());
            boolean replacing = connections != null && connections.size() >= MAX_CONNECTIONS_PER_USER;
            if (!replacing && emitters.values().stream().mapToInt(List::size).sum() >= 1000) {
                throw new LimitExceededException("Too many notification connections. Close another tab and retry.");
            }
            if (replacing) evicted = connections.remove(0);
            emitters.computeIfAbsent(user.getId(), id -> new CopyOnWriteArrayList<>()).add(connection);
        }

        if (evicted != null) {
            try {
                evicted.emitter().send(SseEmitter.event().name("evicted").data(""));
            } catch (Exception subscriberIsGone) {
            } finally {
                closeEmitter(user.getId(), evicted.emitter());
            }
        }

        try {
            emitter.send(SseEmitter.event().name("unread-count").data(new UnreadCountDTO(getUnreadCount(user))));
        } catch (Exception subscriberIsGone) {
            cleanup.run();
            try {
                emitter.complete();
            } catch (Exception responseAlreadyTornDown) {
            }
        }

        return emitter;
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        synchronized (emitters) {
            emitters.computeIfPresent(userId, (id, list) -> {
                list.removeIf(connection -> connection.emitter() == emitter);
                return list.isEmpty() ? null : list;
            });
        }
    }

    public void publishUnreadCount(User recipient) {
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
        CopyOnWriteArrayList<Connection> userEmitters = emitters.get(recipient.getId());
        if (userEmitters == null || userEmitters.isEmpty()) return;

        try {
            UserRepository.SessionState state = userRepository.findSessionStates(List.of(recipient.getId()))
                    .stream().findFirst().orElse(null);
            UnreadCountDTO payload = new UnreadCountDTO(getUnreadCount(recipient));
            sendToAll(recipient.getId(), userEmitters, state, SseEmitter.event().name("unread-count").data(payload));
        } catch (Exception failure) {
            SafeLog.failure(log, "notification_delivery_failed", "INTERNAL_ERROR", failure);
        }
    }

    @Scheduled(fixedRateString = "${app.notifications.sse-heartbeat-ms:5000}")
    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        try {
            Set<Long> ids = Set.copyOf(emitters.keySet());
            Map<Long, UserRepository.SessionState> states = userRepository.findSessionStates(ids).stream()
                    .collect(Collectors.toMap(UserRepository.SessionState::getId, Function.identity()));
            for (Long id : ids) {
                var connections = emitters.get(id);
                if (connections != null) sendToAll(id, connections, states.get(id), SseEmitter.event().name("heartbeat").data(""));
            }
        } catch (Exception failure) {
            SafeLog.failure(log, "notification_heartbeat_failed", "INTERNAL_ERROR", failure);
        }
    }

    public void revokeSessionsAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    closeConnections(userId);
                }
            });
        } else {
            closeConnections(userId);
        }
    }

    private void closeConnections(Long userId) {
        List<Connection> connections;
        synchronized (emitters) {
            connections = emitters.remove(userId);
        }
        if (connections != null) connections.forEach(connection -> closeEmitter(userId, connection.emitter()));
    }

    private void closeEmitter(Long userId, SseEmitter emitter) {
        removeEmitter(userId, emitter);
        try {
            emitter.complete();
        } catch (Exception responseAlreadyTornDown) {
        }
    }

    private void sendToAll(Long userId, CopyOnWriteArrayList<Connection> userEmitters,
                           UserRepository.SessionState state, SseEmitter.SseEventBuilder event) {
        for (Connection connection : userEmitters) {
            SseEmitter emitter = connection.emitter();
            if (state == null || state.getBanned() || !Objects.equals(state.getSessionVersion(), connection.sessionVersion())) {
                closeEmitter(userId, emitter);
                continue;
            }
            try {
                emitter.send(event);
            } catch (Exception subscriberIsGone) {
                closeEmitter(userId, emitter);
            }
        }
    }

    @Transactional
    public void recordEvent(User recipient, String type, Project project, User actor, Date eventDate) {
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
            notification.setCreatedDate(eventDate);
            notification.setUpdatedDate(now);
            notificationRepository.save(notification);
        }
        if (NotificationTypes.UPVOTE.equals(type) && project != null) {
            syncUpvotes(recipient, project);
        } else if (NotificationTypes.FOLLOW.equals(type)) {
            syncFollows(recipient);
        } else if (NotificationTypes.COMMENT.equals(type) && project != null) {
            syncComments(recipient, project);
        } else if (NotificationTypes.FORK.equals(type) && project != null) {
            notificationRepository.syncForks(recipient.getId(), project.getId());
            publishUnreadCount(recipient);
        } else {
            publishUnreadCount(recipient);
        }
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
                user.getId(), Pagination.page(page, size));
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
        publishUnreadCount(user);
    }

    @Transactional
    public void markRead(User user, Long notificationId) {
        if (notificationRepository.markReadByIdAndRecipientId(notificationId, user.getId()) == 0) {
            throw new ResourceNotFoundException("Notification not found");
        }
        publishUnreadCount(user);
    }

    @Transactional
    public void syncUpvotes(User recipient, Project project) {
        notificationRepository.syncUpvotes(recipient.getId(), project.getId());
        notificationRepository.deleteEmptyUpvotes(recipient.getId(), project.getId());
        publishUnreadCount(recipient);
    }

    @Transactional
    public void syncFollows(User recipient) {
        notificationRepository.syncFollows(recipient.getId());
        notificationRepository.deleteEmptyActivity(recipient.getId(), NotificationTypes.FOLLOW, null);
        publishUnreadCount(recipient);
    }

    @Transactional
    public void syncComments(User recipient, Project project) {
        notificationRepository.syncComments(recipient.getId(), project.getId());
        notificationRepository.deleteEmptyActivity(recipient.getId(), NotificationTypes.COMMENT, project.getId());
        publishUnreadCount(recipient);
    }

    @Transactional
    public void deleteNotification(User user, Long notificationId) {
        long deleted = notificationRepository.deleteByIdAndRecipientId(notificationId, user.getId());
        if (deleted == 0) {
            throw new ResourceNotFoundException("Notification not found");
        }
        publishUnreadCount(user);
    }
}
