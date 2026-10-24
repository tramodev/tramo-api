package com.tramo.backend.notification;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.PrivacyPolicy;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SseConnectionLimitTest {
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = new NotificationService(mock(NotificationRepository.class),
            mock(ProjectIdCodec.class), 1800000, mock(PrivacyPolicy.class), users);

    SseConnectionLimitTest() {
        when(users.findSessionStates(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> state(id, null, false)).toList();
        });
    }

    private UserRepository.SessionState state(long id, UUID version, boolean banned) {
        UserRepository.SessionState state = mock(UserRepository.SessionState.class);
        when(state.getId()).thenReturn(id);
        when(state.getSessionVersion()).thenReturn(version);
        when(state.getBanned()).thenReturn(banned);
        return state;
    }

    private User user(long id) { User user = new User(); user.setId(id); return user; }

    @Test
    void perUserLimitIsAtomicAndOtherUsersRemainIndependent() throws Exception {
        var executor = Executors.newFixedThreadPool(12);
        try {
            var attempts = IntStream.range(0, 20).mapToObj(i -> executor.submit(() -> {
                try { notifications.subscribe(user(1)); return true; }
                catch (LimitExceededException full) { return false; }
            })).toList();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            assertThat(accepted).isEqualTo(20);
            var connections = (java.util.Map<?, ?>) ReflectionTestUtils.getField(notifications, "emitters");
            assertThat((List<?>) connections.get(1L)).hasSize(5);
            assertThatCode(() -> notifications.subscribe(user(2))).doesNotThrowAnyException();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void instanceLimitCoversAllUsers() {
        for (int id = 1; id <= 1000; id++) notifications.subscribe(user(id));
        assertThatThrownBy(() -> notifications.subscribe(user(1001))).isInstanceOf(LimitExceededException.class);
    }

    @Test
    void completionAndTimeoutReleaseTheSlotOnlyOnce() {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 5; i++) notifications.subscribe(user(1));
            var completion = ArgumentCaptor.forClass(Runnable.class);
            var timeout = ArgumentCaptor.forClass(Runnable.class);
            SseEmitter emitter = construction.constructed().get(0);
            verify(emitter).onCompletion(completion.capture());
            verify(emitter).onTimeout(timeout.capture());
            completion.getValue().run();
            timeout.getValue().run();
            notifications.subscribe(user(1));
            verify(construction.constructed().get(1), never()).complete();
            notifications.subscribe(user(1));
            verify(construction.constructed().get(1)).complete();
        }
    }

    @Test
    void failedHeartbeatReleasesSlotBeforeCompletionAndCannotStopTheLoop() throws Exception {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 5; i++) notifications.subscribe(user(1));
            SseEmitter emitter = construction.constructed().get(0);
            doThrow(new IOException("gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            doAnswer(call -> {
                notifications.subscribe(user(1));
                throw new IllegalStateException("response gone");
            }).when(emitter).complete();
            assertThatCode(notifications::sendHeartbeat).doesNotThrowAnyException();
            verify(emitter).complete();
            verify(construction.constructed().get(1), never()).complete();
            notifications.subscribe(user(1));
            verify(construction.constructed().get(1)).complete();
        }
    }

    @Test
    void evictsOldestAndStillCompletesWhenItsEventCannotBeSent() throws Exception {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 5; i++) notifications.subscribe(user(1));
            SseEmitter oldest = construction.constructed().get(0);
            doThrow(new IOException("gone")).when(oldest).send(any(SseEmitter.SseEventBuilder.class));
            doThrow(new IllegalStateException("gone")).when(oldest).complete();
            assertThatCode(() -> notifications.subscribe(user(1))).doesNotThrowAnyException();
            verify(oldest).complete();
            verify(construction.constructed().get(1), never()).complete();
            notifications.subscribe(user(1));
            verify(construction.constructed().get(1)).complete();
        }
    }

    @Test
    void replacementIsAllowedAtTheInstanceLimit() {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 5; i++) notifications.subscribe(user(1));
            for (int id = 2; id <= 996; id++) notifications.subscribe(user(id));
            assertThatCode(() -> notifications.subscribe(user(1))).doesNotThrowAnyException();
            verify(construction.constructed().get(0)).complete();
            assertThatThrownBy(() -> notifications.subscribe(user(997))).isInstanceOf(LimitExceededException.class);
        }
    }

    @Test
    void errorCallbackReleasesTheSlot() {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 5; i++) notifications.subscribe(user(1));
            var error = ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(construction.constructed().get(0)).onError(error.capture());
            error.getValue().accept(new IOException("gone"));
            notifications.subscribe(user(1));
            verify(construction.constructed().get(1), never()).complete();
        }
    }

    @Test
    void heartbeatClosesRevokedBannedAndDeletedSessionsWithoutSendingAnotherEvent() throws Exception {
        try (var construction = mockConstruction(SseEmitter.class)) {
            for (long id = 1; id <= 4; id++) notifications.subscribe(user(id));
            doReturn(List.of(state(1, UUID.randomUUID(), false), state(2, null, true), state(4, null, false)))
                    .when(users).findSessionStates(any());
            notifications.sendHeartbeat();
            for (int i = 0; i < 3; i++) {
                verify(construction.constructed().get(i)).complete();
                verify(construction.constructed().get(i), times(1)).send(any(SseEmitter.SseEventBuilder.class));
            }
            verify(construction.constructed().get(3), never()).complete();
            verify(construction.constructed().get(3), times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test
    void repositoryFailureCannotStopHeartbeatsOrSendUnverifiedEvents() throws Exception {
        try (var construction = mockConstruction(SseEmitter.class)) {
            notifications.subscribe(user(1));
            doThrow(new IllegalStateException("database unavailable")).when(users).findSessionStates(any());
            assertThatCode(notifications::sendHeartbeat).doesNotThrowAnyException();
            verify(construction.constructed().get(0), times(1)).send(any(SseEmitter.SseEventBuilder.class));
            doReturn(List.of(state(1, null, false))).when(users).findSessionStates(any());
            notifications.sendHeartbeat();
            verify(construction.constructed().get(0), times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }
}
