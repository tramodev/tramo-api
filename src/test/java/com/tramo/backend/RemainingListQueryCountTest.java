package com.tramo.backend;

import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// N+1 guards for the remaining collection endpoints (everything not already covered
// by project/QueryCountTest, EditorQueryCountTest, and the comment/notification guards).
//
// Two levers, picked per endpoint:
//  - endpoints that build the whole result in memory (dashboards, feeds, activity,
//    admin lists) are grown by TOTAL row count.
//  - endpoints paged by the database only ever materialise `size` rows, so a per-row
//    N+1 is bounded by the page size, not the total — those are grown by PAGE SIZE.
class RemainingListQueryCountTest extends AbstractIntegrationTest {

    private void action(User u, String url) throws Exception {
        mockMvc.perform(post(url).header("Authorization", bearer(u))).andExpect(status().isOk());
    }

    private Project published(User owner, String title, String tags) {
        return createProject(owner, title, "published", "A description", tags);
    }

    // ---- endpoints grown by total row count ----

    @Test
    void dashboardProjectsQueryCountDoesNotScaleWithProjectCount() throws Exception {
        User owner = createUser("rlqcdash");
        createProject(owner, "Dash 0", "private");

        long small = queryCount(() -> mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createProject(owner, "Dash " + i, "private");
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void publishedFeedQueryCountDoesNotScaleWithFeedSize() throws Exception {
        User author = createUser("rlqcfeed");
        published(author, "Feed 0", null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/projects"))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            published(author, "Feed " + i, null);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/projects"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void activityQueryCountDoesNotScaleWithActivityCount() throws Exception {
        User me = createUser("rlqcactme");
        User other = createUser("rlqcactother");
        action(me, "/api/project/" + pid(published(other, "Act 0", null)) + "/vote");

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/activity?page=0&size=2")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            action(me, "/api/project/" + pid(published(other, "Act " + i, null)) + "/vote");
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/activity?page=0&size=2")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void adminReportsQueryCountDoesNotScaleWithReportCount() throws Exception {
        User admin = createAdmin("rlqcadmin");
        User reporter = createUser("rlqcreporter");
        reportProject(reporter, published(createUser("rlqcpauthor0"), "Rep 0", null));

        long small = queryCount(() -> mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            reportProject(reporter, published(createUser("rlqcpauthor" + i), "Rep " + i, null));
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void adminUserSearchQueryCountDoesNotScaleWithUserCount() throws Exception {
        User admin = createAdmin("rlqcusearchadmin");

        long small = queryCount(() -> mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 5; i++) {
            createUser("rlqcusearch" + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void hotTopicsQueryCountDoesNotScaleWithTaggedProjectCount() throws Exception {
        User author = createUser("rlqctags");
        published(author, "Tag 0", "java,testing");

        long small = queryCount(() -> mockMvc.perform(get("/api/public/tags"))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            published(author, "Tag " + i, "java,spring,testing");
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/tags"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    // ---- database-paged endpoints grown by page size ----

    @Test
    void publicPublishedPageQueryCountDoesNotScaleWithPageSize() throws Exception {
        User author = createUser("rlqcpubuser");
        for (int i = 0; i < 7; i++) {
            published(author, "Pub " + i, null);
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcpubuser/published?page=0&size=2"))
                .andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcpubuser/published?page=0&size=6"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void followersQueryCountDoesNotScaleWithPageSize() throws Exception {
        User target = createUser("rlqcfollowed");
        for (int i = 0; i < 7; i++) {
            User follower = createUser("rlqcfollower" + i);
            action(follower, "/api/users/rlqcfollowed/follow");
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcfollowed/followers?page=0&size=2"))
                .andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcfollowed/followers?page=0&size=6"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void followingQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("rlqcfollowing");
        for (int i = 0; i < 7; i++) {
            createUser("rlqcfollowtarget" + i);
            action(me, "/api/users/rlqcfollowtarget" + i + "/follow");
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcfollowing/following?page=0&size=2"))
                .andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/rlqcfollowing/following?page=0&size=6"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void blockedListQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("rlqcblocker");
        for (int i = 0; i < 7; i++) {
            createUser("rlqcblocked" + i);
            action(me, "/api/users/rlqcblocked" + i + "/block");
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/users/blocked?page=0&size=2")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get("/api/users/blocked?page=0&size=6")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    private void reportProject(User reporter, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().isOk());
    }
}
