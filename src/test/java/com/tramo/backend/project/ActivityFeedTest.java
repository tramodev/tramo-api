package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivityFeedTest extends AbstractIntegrationTest {

    private Project published(User owner, String title) {
        return createProject(owner, title, "published", "A description", null);
    }

    private void action(User user, String url) throws Exception {
        mockMvc.perform(post(url).header("Authorization", bearer(user))).andExpect(status().isOk());
    }

    private void block(User blocker, String target) throws Exception {
        mockMvc.perform(post("/api/users/" + target + "/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions activity(User user, int page, int size) throws Exception {
        return mockMvc.perform(get("/api/profile/activity?page=" + page + "&size=" + size)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());
    }

    @Test
    void ownActionsAppearAsPublishedForkedVotedAndBookmarked() throws Exception {
        User me = createUser("actme1");
        User other = createUser("actother1");

        published(me, "Mine published");
        Project theirs = published(other, "Theirs");
        action(me, "/api/project/" + pid(theirs) + "/vote");
        action(me, "/api/project/" + pid(theirs) + "/bookmark");
        mockMvc.perform(post("/api/project/" + pid(theirs) + "/fork").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.content[*].type",
                        containsInAnyOrder("published", "forked", "voted", "bookmarked")));
    }

    @Test
    void actionsOfOthersOnMyProjectsAppearAsReceivedEvents() throws Exception {
        User me = createUser("actme2");
        User other = createUser("actother2");
        Project mine = published(me, "Mine");

        action(other, "/api/project/" + pid(mine) + "/vote");
        action(other, "/api/project/" + pid(mine) + "/bookmark");
        mockMvc.perform(post("/api/project/" + pid(mine) + "/fork").header("Authorization", bearer(other)))
                .andExpect(status().isOk());

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[*].type",
                        containsInAnyOrder("published", "received_vote", "received_bookmark", "received_fork")));
    }

    @Test
    void receivedEventsCarryTheOtherUsernameAndMyProject() throws Exception {
        User me = createUser("actme3");
        User other = createUser("actother3");
        Project mine = published(me, "Voted project");

        action(other, "/api/project/" + pid(mine) + "/vote");

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[?(@.type=='received_vote')].otherUsername")
                        .value("actother3"))
                .andExpect(jsonPath("$.content[?(@.type=='received_vote')].projectTitle")
                        .value("Voted project"))
                .andExpect(jsonPath("$.content[?(@.type=='received_vote')].projectId")
                        .value(pid(mine)));
    }

    @Test
    void forkedEventNamesTheSourceOwner() throws Exception {
        User me = createUser("actme4");
        User other = createUser("actother4");
        Project theirs = published(other, "Source");

        mockMvc.perform(post("/api/project/" + pid(theirs) + "/fork").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[?(@.type=='forked')].otherUsername").value("actother4"));
    }

    @Test
    void myOwnActionsOnMyOwnProjectsDoNotShowAsReceived() throws Exception {
        User me = createUser("actme5");
        Project mine = published(me, "Self");

        action(me, "/api/project/" + pid(mine) + "/bookmark");

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[*].type", not(org.hamcrest.Matchers.hasItem("received_bookmark"))))
                .andExpect(jsonPath("$.content[*].type",
                        containsInAnyOrder("published", "bookmarked")));
    }

    @Test
    void newestActivityComesFirst() throws Exception {
        User me = createUser("actme6");
        User other = createUser("actother6");
        Project first = published(other, "First");
        action(me, "/api/project/" + pid(first) + "/vote");
        Thread.sleep(10);
        Project second = published(other, "Second");
        action(me, "/api/project/" + pid(second) + "/vote");

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[0].projectTitle").value("Second"))
                .andExpect(jsonPath("$.content[1].projectTitle").value("First"));
    }

    @Test
    void activityIsPaginated() throws Exception {
        User me = createUser("actme7");
        User other = createUser("actother7");
        for (int i = 0; i < 2; i++) {
            action(me, "/api/project/" + pid(published(other, "V" + i)) + "/vote");
            action(me, "/api/project/" + pid(published(other, "B" + i)) + "/bookmark");
        }

        activity(me, 0, 2)
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true));

        activity(me, 1, 2)
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void emptyActivityReturnsEmptyPage() throws Exception {
        User me = createUser("actme8");

        activity(me, 0, 10)
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void blockedUsersDisappearFromReceivedActivity() throws Exception {
        User me = createUser("actme9");
        User other = createUser("actother9");
        Project mine = published(me, "Mine");
        action(other, "/api/project/" + pid(mine) + "/vote");

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[*].type", org.hamcrest.Matchers.hasItem("received_vote")));

        block(me, "actother9");

        activity(me, 0, 20)
                .andExpect(jsonPath("$.content[*].otherUsername", everyItem(not("actother9"))))
                .andExpect(jsonPath("$.content[*].type", not(org.hamcrest.Matchers.hasItem("received_vote"))));
    }

    @Test
    void activityRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/profile/activity")).andExpect(status().isUnauthorized());
    }
}
