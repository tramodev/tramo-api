package com.tramo.backend.notification;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationPurgeTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationRepository notificationRepository;

    private Project published(User owner, String title) {
        return createProject(owner, title, "published", "A description", null);
    }

    private void voteOn(User voter, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(voter)))
                .andExpect(status().isOk());
    }

    private void ageAllNotifications(int days) {
        jdbcTemplate.update("UPDATE notification SET created_date = created_date - make_interval(days => ?), updated_date = updated_date - make_interval(days => ?)", days, days);
    }

    @Test
    void purgeRemovesReadNotificationsPastRetention() throws Exception {
        User owner = createUser("purgeowner1");
        User voter = createUser("purgevoter1");
        voteOn(voter, published(owner, "Noted"));
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        assertThat(notificationRepository.count()).isPositive();
        ageAllNotifications(120);

        notificationService.purgeOldReadNotifications();

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void purgeKeepsUnreadNotificationsRegardlessOfAge() throws Exception {
        User owner = createUser("purgeowner2");
        User voter = createUser("purgevoter2");
        voteOn(voter, published(owner, "Unread"));
        long before = notificationRepository.count();
        assertThat(before).isPositive();
        ageAllNotifications(120);

        notificationService.purgeOldReadNotifications();

        assertThat(notificationRepository.count()).isEqualTo(before);
    }

    @Test
    void purgeKeepsRecentReadNotifications() throws Exception {
        User owner = createUser("purgeowner3");
        User voter = createUser("purgevoter3");
        voteOn(voter, published(owner, "Recent"));
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        long before = notificationRepository.count();
        assertThat(before).isPositive();

        notificationService.purgeOldReadNotifications();

        assertThat(notificationRepository.count()).isEqualTo(before);
    }

    @Test
    void purgeOnAnEmptyTableIsANoop() {
        notificationService.purgeOldReadNotifications();

        assertThat(notificationRepository.count()).isZero();
    }
}
