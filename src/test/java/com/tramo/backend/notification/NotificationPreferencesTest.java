package com.tramo.backend.notification;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationPreferencesTest extends AbstractIntegrationTest {

    private void setPreference(User user, String json) throws Exception {
        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void assertUnreadCount(User user, int expected) throws Exception {
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(expected));
    }

    private Project publishedProject(User owner, String title) {
        return createProject(owner, title, "published");
    }

    @Test
    void notificationsAreOnByDefault() throws Exception {
        var user = createUser("notifprefdefault");

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.mutedNotificationTypes.length()").value(0));
    }

    @Test
    void mutingATypeStopsThatNotificationButLeavesTheOthers() throws Exception {
        var owner = createUser("notifmuteowner");
        var fan = createUser("notifmutefan");
        Project project = publishedProject(owner, "Muted upvotes");
        setPreference(owner, """
                {"mutedNotificationTypes":["UPVOTE","BADGE"]}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        assertUnreadCount(owner, 0);

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(fan))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"still get this one"}"""))
                .andExpect(status().isOk());
        assertUnreadCount(owner, 1);

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("COMMENT"));
    }

    @Test
    void disablingNotificationsSilencesEveryType() throws Exception {
        var owner = createUser("notifoffowner");
        var fan = createUser("notiffofffan");
        Project project = publishedProject(owner, "All quiet");
        setPreference(owner, """
                {"notificationsEnabled":false}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(fan))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"hello"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/notifoffowner/follow").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        assertUnreadCount(owner, 0);
    }

    @Test
    void mutingSurvivesTheMasterToggleGoingOffAndBackOn() throws Exception {
        var owner = createUser("notifrestore");
        setPreference(owner, """
                {"mutedNotificationTypes":["FORK","FOLLOW"]}""");
        setPreference(owner, """
                {"notificationsEnabled":false}""");
        setPreference(owner, """
                {"notificationsEnabled":true}""");

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.mutedNotificationTypes.length()").value(2));
    }

    @Test
    void mutingFollowStopsTheFollowNotification() throws Exception {
        var target = createUser("notifmutefollow");
        var follower = createUser("notiffollower");
        setPreference(target, """
                {"mutedNotificationTypes":["FOLLOW"]}""");

        mockMvc.perform(post("/api/users/notifmutefollow/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());

        assertUnreadCount(target, 0);
    }

    @Test
    void mutingPublishStopsTheFanOutForThatFollowerOnly() throws Exception {
        var author = createUser("notiffanoutauthor");
        var quiet = createUser("notifquietfollower");
        var loud = createUser("notifloudfollower");
        mockMvc.perform(post("/api/users/notiffanoutauthor/follow").header("Authorization", bearer(quiet)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/notiffanoutauthor/follow").header("Authorization", bearer(loud)))
                .andExpect(status().isOk());
        setPreference(quiet, """
                {"mutedNotificationTypes":["PUBLISH"]}""");

        Project project = createProject(author, "Fan out", "private", "a description", null);
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        assertUnreadCount(quiet, 0);
        assertUnreadCount(loud, 1);
    }

    @Test
    void anUnknownNotificationTypeIsRejected() throws Exception {
        var user = createUser("notifbadtype");

        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mutedNotificationTypes":["NONSENSE"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mutedTypesRoundTripThroughThePreferencesEndpoint() throws Exception {
        var user = createUser("notifroundtrip");

        setPreference(user, """
                {"mutedNotificationTypes":["badge","Featured"]}""");

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutedNotificationTypes[0]").value("BADGE"))
                .andExpect(jsonPath("$.mutedNotificationTypes[1]").value("FEATURED"));

        setPreference(user, """
                {"mutedNotificationTypes":[]}""");

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutedNotificationTypes.length()").value(0));
    }
}
