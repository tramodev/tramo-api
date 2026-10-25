// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.exception.GlobalExceptionHandler;
import org.slf4j.LoggerFactory;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ExploreService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationTest extends AbstractIntegrationTest {

    @Autowired
    ExploreService exploreService;

    
    
    
    
    
    
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
        long badgeNotificationId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.content[0].id")).longValue();
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
                .andExpect(jsonPath("$.content[0].type").value("UPVOTE"))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("notiffan"))
                .andExpect(jsonPath("$.content[0].count").value(1))
                .andExpect(jsonPath("$.content[0].read").value(false));
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
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].count").value(2));
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
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.content[0].id")).longValue();

        mockMvc.perform(delete("/api/notifications/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
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
                .andExpect(jsonPath("$.content[0].type").value("PUBLISH"))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("notifpublisher"))
                .andExpect(jsonPath("$.content[0].projectTitle").value("Publish me"));

        
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Publish me v2","visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        
        
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
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void legacyPublishedProjectGetsStampedWithoutEverNotifying() throws Exception {
        User owner = createUser("legacypublisher");
        User follower = createUser("legacypublisherfan");
        mockMvc.perform(post("/api/users/legacypublisher/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());

        
        
        Project project = createProject(owner, "Legacy project", "published", "A description", null);

        
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        
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
                .andExpect(jsonPath("$.content.length()").value(0));
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
                .andExpect(jsonPath("$.content[0].type").value("SHARE"))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("notifsharer"))
                .andExpect(jsonPath("$.content[0].projectTitle").value("Share me"));

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void repeatedSharesMergeIntoOneNotificationPerFollower() throws Exception {
        User owner = createUser("notifmergeowner");
        User sharer = createUser("notifmergesharer");
        User firstFollower = createUser("notifmergefan1");
        User secondFollower = createUser("notifmergefan2");
        for (User follower : List.of(firstFollower, secondFollower)) {
            mockMvc.perform(post("/api/users/notifmergesharer/follow").header("Authorization", bearer(follower)))
                    .andExpect(status().isOk());
        }

        Project project = publishedProject(owner, "Shared twice");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/share").header("Authorization", bearer(sharer)))
                    .andExpect(status().isOk());
        }

        for (User follower : List.of(firstFollower, secondFollower)) {
            mockMvc.perform(get("/api/notifications").header("Authorization", bearer(follower)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].type").value("SHARE"))
                    .andExpect(jsonPath("$.content[0].count").value(2));
        }
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
                .andExpect(jsonPath("$.content[?(@.type=='BADGE')]").exists());
    }

    @Test
    void listNotificationsQueryCountDoesNotScaleWithNotificationCount() throws Exception {
        User owner = createUser("nqcowner");

        
        
        
        voteOnFreshProject(owner, 0);

        
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        long small = queryCount(() -> mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            voteOnFreshProject(owner, i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    private void voteOnFreshProject(User owner, int i) throws Exception {
        Project project = createProject(owner, "Notif QC " + i, "published", "d", null);
        User fan = createUser("nqcfan" + i);
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
    }

    @Test
    void refreshFeaturedProjectNotifiesNewlyFeaturedOwner() throws Exception {
        User owner = createUser("notiffeatured");
        User fan = createUser("notiffeaturedfan");
        Project project = publishedProject(owner, "Feature me");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        exploreService.refreshFeaturedProject();

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.type=='FEATURED')]").exists());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void togglingVoteDoesNotInflateNotification() throws Exception {
        User owner = createUser("toggleowner");
        User fan = createUser("togglefan");
        Project project = publishedProject(owner, "Toggle");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1));
    }

    @Test
    void blockedNotificationsDoNotCountOrConsumePage() throws Exception {
        User owner = createUser("blockedowner");
        User fan = createUser("blockedfan");
        Project project = publishedProject(owner, "Blocked");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        var stream = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(owner)))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(post("/api/users/blockedfan/block").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(stream.getResponse().getContentAsString()).contains("\"unreadCount\":0");
        mockMvc.perform(get("/api/notifications?size=1").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void readingAllPublishesUpdatedCount() throws Exception {
        User owner = createUser("readstreamowner");
        User fan = createUser("readstreamfan");
        Project project = publishedProject(owner, "Read stream");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        var stream = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(owner)))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        assertThat(stream.getResponse().getContentAsString()).contains("\"unreadCount\":0");
    }

    @Test
    void onlyRecipientCanReadOneNotification() throws Exception {
        User owner = createUser("singleowner");
        User fan = createUser("singlefan");
        Project project = publishedProject(owner, "Single");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        String body = mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.content[0].id")).longValue();
        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", bearer(fan)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void removingVotesUpdatesActorAndRemovesEmptyNotification() throws Exception {
        User owner = createUser("removeowner");
        User first = createUser("removefirst");
        User last = createUser("removelast");
        Project project = publishedProject(owner, "Remove vote");
        for (User voter : List.of(first, last, last)) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(voter)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("removefirst"));
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(first)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void deletingPublishesUpdatedCountToEveryStream() throws Exception {
        User owner = createUser("deletestreamowner");
        User fan = createUser("deletestreamfan");
        Project project = publishedProject(owner, "Delete stream");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        var first = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(owner)))
                .andExpect(request().asyncStarted()).andReturn();
        var second = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(owner)))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.content[0].id")).longValue();
        mockMvc.perform(delete("/api/notifications/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        assertThat(first.getResponse().getContentAsString()).contains("\"unreadCount\":0");
        assertThat(second.getResponse().getContentAsString()).contains("\"unreadCount\":0");
    }


    @Test
    void followTogglesCountOnlyCurrentUnreadFollowers() throws Exception {
        User owner = createUser("followcountowner");
        User first = createUser("followcountfirst");
        User second = createUser("followcountsecond");
        for (User actor : List.of(first, second, first, first)) {
            mockMvc.perform(post("/api/users/followcountowner/follow").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(2));
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/users/followcountowner/follow").header("Authorization", bearer(first)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1))
                .andExpect(jsonPath("$.content[0].read").value(false));
        mockMvc.perform(post("/api/users/followcountowner/follow").header("Authorization", bearer(first)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].read").value(true));
    }

    @Test
    void repeatedCommentsCountDistinctUnreadAuthorsAndDeletingUpdatesTheActor() throws Exception {
        User owner = createUser("commentcountowner");
        User first = createUser("commentcountfirst");
        User second = createUser("commentcountsecond");
        Project project = publishedProject(owner, "Comment count");
        long lastId = 0;
        for (User actor : List.of(first, second, first)) {
            String body = mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                            .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"hello\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            lastId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
        }
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(2))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("commentcountfirst"));
        mockMvc.perform(delete("/api/comment/" + lastId).header("Authorization", bearer(first)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(2))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("commentcountsecond"));
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(second)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"another\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1));
    }

    @Test
    void newVotesExcludePreviouslyReadAndDismissedVotes() throws Exception {
        User owner = createUser("votewindowowner");
        User first = createUser("votewindowfirst");
        User second = createUser("votewindowsecond");
        User third = createUser("votewindowthird");
        Project project = publishedProject(owner, "Vote window");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(first)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/notifications/read").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(second)))
                .andExpect(status().isOk());
        String body = mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("votewindowsecond"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.content[0].id")).longValue();
        mockMvc.perform(delete("/api/notifications/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(third)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(first)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.content[0].count").value(1))
                .andExpect(jsonPath("$.content[0].latestActorUsername").value("votewindowthird"));
    }

    @Test
    void unsupportedNotificationMethodsReturn405AndAllowHeader() throws Exception {
        User owner = createUser("notificationmethods");
        for (var request : List.of(get("/api/notifications/123"), put("/api/notifications/123"),
                patch("/api/notifications/123"))) {
            mockMvc.perform(request.header("Authorization", bearer(owner)))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Allow", "DELETE"))
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }


    @Test
    void deletingReadOnlyNotificationRoutesReturns405() throws Exception {
        User owner = createUser("notificationreadonly");
        for (String path : List.of("unread-count", "stream")) {
            mockMvc.perform(delete("/api/notifications/" + path).header("Authorization", bearer(owner)))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Allow", org.hamcrest.Matchers.containsString("GET")))
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }


    @Test
    void missingRoutesReturn404WithoutLoggingServerErrors() throws Exception {
        User owner = createUser("notificationmissing");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (String path : List.of("/api/notifications/abc", "/api/notifications/-1", "/api/nonexistent")) {
                for (var request : List.of(get(path), delete(path))) {
                    mockMvc.perform(request.header("Authorization", bearer(owner)))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                            .andExpect(jsonPath("$.message").value("Resource not found"));
                }
            }
            assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

}
