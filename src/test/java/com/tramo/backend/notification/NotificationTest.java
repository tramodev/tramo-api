package com.tramo.backend.notification;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationTest extends AbstractIntegrationTest {

    @Autowired
    ProjectService projectService;

    // Publishing via the real update() flow (not the createProject(..., "published", ...) test
    // helper, which inserts directly) fires the first_publish badge check right away instead of
    // leaving it to fire lazily on whatever the next checkAndAwardBadges call happens to be —
    // otherwise the first vote a project ever receives ends up producing a BADGE notification on
    // top of the UPVOTE one, throwing off the notification-list/unread-count assertions below.
    // Mark-as-read isn't enough since getNotifications() returns read ones too; delete it outright.
    private Project publishedProject(User owner, String title) throws Exception {
        Project project = createProject(owner, title, "private", "d", null);
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long badgeNotificationId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$[0].id")).longValue();
        mockMvc.perform(delete("/api/notifications/" + badgeNotificationId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        return project;
    }

    @Test
    void voteGeneratesNotificationAndUpdatesUnreadCount() throws Exception {
        User owner = createUser("notifowner");
        User fan = createUser("notiffan");
        Project project = publishedProject(owner, "Notify me");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("UPVOTE"))
                .andExpect(jsonPath("$[0].latestActorUsername").value("notiffan"))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void repeatedVotesFromDifferentUsersIncrementSameNotification() throws Exception {
        User owner = createUser("notifowner2");
        User fan1 = createUser("notiffan2a");
        User fan2 = createUser("notiffan2b");
        Project project = publishedProject(owner, "Notify me too");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan1)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan2)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(2));
    }

    @Test
    void votingOnOwnProjectDoesNotNotifySelf() throws Exception {
        User owner = createUser("notifself");
        Project project = publishedProject(owner, "Mine");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void markAllReadClearsUnreadCount() throws Exception {
        User owner = createUser("notifreader");
        User fan = createUser("notifreaderfan");
        Project project = publishedProject(owner, "Read me");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void deleteNotificationRemovesIt() throws Exception {
        User owner = createUser("notifdeleter");
        User fan = createUser("notifdeleterfan");
        Project project = publishedProject(owner, "Delete me");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$[0].id")).longValue();

        mockMvc.perform(delete("/api/notifications/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deletingUnknownOrOthersNotificationReturnsNotFound() throws Exception {
        User owner = createUser("notifdeleter2");
        mockMvc.perform(delete("/api/notifications/999999").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishingNotifiesFollowersButNotOnRepublish() throws Exception {
        User owner = createUser("notifpublisher");
        User follower = createUser("notifpublisherfollower");
        mockMvc.perform(post("/api/users/notifpublisher/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());

        Project project = createProject(owner, "Publish me", "private", "A description", null);
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PUBLISH"))
                .andExpect(jsonPath("$[0].latestActorUsername").value("notifpublisher"))
                .andExpect(jsonPath("$[0].projectTitle").value("Publish me"));

        // re-saving while already published (e.g. renaming) must not re-fire the follower notification
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Publish me v2","visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // private -> published again must not re-notify either, even once the first
        // notification is read (unread-dedup no longer applies): PUBLISH is first-publish-only
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"private"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void legacyPublishedProjectGetsStampedWithoutEverNotifying() throws Exception {
        User owner = createUser("legacypublisher");
        User follower = createUser("legacypublisherfan");
        mockMvc.perform(post("/api/users/legacypublisher/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());

        // direct DB insert while already published — firstPublishedDate stays null, exactly
        // like rows published before first-publish tracking existed
        Project project = createProject(owner, "Legacy project", "published", "A description", null);

        // re-saving while published must stamp firstPublishedDate silently, not notify
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // and once stamped, a private -> published cycle must not notify either
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"private"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sharingNotifiesSharersFollowersNotProjectOwner() throws Exception {
        User owner = createUser("notifshareowner");
        User sharer = createUser("notifsharer");
        User sharerFollower = createUser("notifsharerfollower");
        mockMvc.perform(post("/api/users/notifsharer/follow").header("Authorization", bearer(sharerFollower)))
                .andExpect(status().isOk());

        Project project = publishedProject(owner, "Share me");

        mockMvc.perform(post("/api/project/" + pid(project) + "/share").header("Authorization", bearer(sharer)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(sharerFollower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SHARE"))
                .andExpect(jsonPath("$[0].latestActorUsername").value("notifsharer"))
                .andExpect(jsonPath("$[0].projectTitle").value("Share me"));

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void sharingUnviewableProjectFails() throws Exception {
        User owner = createUser("notifshareowner2");
        User sharer = createUser("notifsharer2");
        Project project = createProject(owner, "Private project", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/share").header("Authorization", bearer(sharer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamEndpointStartsAsyncAndReceivesLiveUpdate() throws Exception {
        User owner = createUser("notifstream");
        User fan = createUser("notifstreamfan");
        Project project = publishedProject(owner, "Stream me");

        mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void publishingFirstProjectAwardsBadgeNotification() throws Exception {
        User author = createUser("notifbadge");
        Project project = createProject(author, "Badge me", "private", "A description", null);

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='BADGE')]").exists());
    }

    @Test
    void refreshFeaturedProjectNotifiesNewlyFeaturedOwner() throws Exception {
        User owner = createUser("notiffeatured");
        User fan = createUser("notiffeaturedfan");
        Project project = publishedProject(owner, "Feature me");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        projectService.refreshFeaturedProject();

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='FEATURED')]").exists());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().isFeatured()).isTrue();
    }
}
