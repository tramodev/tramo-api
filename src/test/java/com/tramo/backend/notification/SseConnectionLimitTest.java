package com.tramo.backend.notification;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.PrivacyPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SseConnectionLimitTest {
    private final NotificationService notifications = new NotificationService(mock(NotificationRepository.class),
            mock(ProjectIdCodec.class), 1800000, mock(PrivacyPolicy.class));

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
            assertThat(accepted).isEqualTo(5);
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
            assertThatThrownBy(() -> notifications.subscribe(user(1))).isInstanceOf(LimitExceededException.class);
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
            assertThatThrownBy(() -> notifications.subscribe(user(1))).isInstanceOf(LimitExceededException.class);
        }
    }
}
