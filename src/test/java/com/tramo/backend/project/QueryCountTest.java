// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.notification.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueryCountTest extends AbstractIntegrationTest {
    @Autowired NotificationService notifications;
    @Autowired com.tramo.backend.project.service.ProjectPublishService publishService;

    @Test
    void publicPreviewCursorQueryCountDoesNotScaleWithPageSize() throws Exception {
        User owner = createUser("qccursorpage");
        for (int i = 0; i < 10; i++) seedPublishedProject(owner, "Cursor " + i, 1, 1, null);
        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/qccursorpage/published").param("size", "1"))
                .andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/qccursorpage/published").param("size", "10"))
                .andExpect(status().isOk()));
        assertThat(large).isEqualTo(small);
    }

    @Test
    void publicSnapshotThumbnailsDoNotAddQueriesPerTrailOrNote() throws Exception {
        User owner = createUser("qcsnapshotthumb");
        Project project = seedPublishedProject(owner, "Thumbnail", 1, 1, null);
        jdbcTemplate.update("update project set thumbnail_type = 'GRAPH' where id = ?", project.getId());
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/qcsnapshotthumb/published"))
                .andExpect(status().isOk()));
        for (int i = 0; i < 5; i++) {
            long trail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Extra\"}");
            for (int j = 0; j < 4; j++) postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"Note\"}");
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/qcsnapshotthumb/published"))
                .andExpect(status().isOk()));
        assertThat(large).isEqualTo(small);
    }

    @Test
    void publicSiteContentUsesOneQueryPerSectionRegardlessOfEntryCount() throws Exception {
        long small = queryCount(() -> {
            mockMvc.perform(get("/api/public/content/blog")).andExpect(status().isOk());
            mockMvc.perform(get("/api/public/content/changelog")).andExpect(status().isOk());
            mockMvc.perform(get("/api/public/content/roadmap")).andExpect(status().isOk());
        });
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update("INSERT INTO blog_post (slug, title, summary, category, content, published, published_date) VALUES (?, 'Title', 'Summary', 'Ideas', 'Body', true, CURRENT_DATE)", "post-" + i);
            jdbcTemplate.update("INSERT INTO changelog_entry (title, content, published, published_date) VALUES ('Update', 'Changes', true, CURRENT_DATE)");
            jdbcTemplate.update("INSERT INTO roadmap_item (title, description, status, position, published) VALUES ('Plan', 'More', 'PLANNED', ?, true)", i);
        }
        long large = queryCount(() -> {
            mockMvc.perform(get("/api/public/content/blog")).andExpect(status().isOk());
            mockMvc.perform(get("/api/public/content/changelog")).andExpect(status().isOk());
            mockMvc.perform(get("/api/public/content/roadmap")).andExpect(status().isOk());
        });
        assertThat(small).isEqualTo(3);
        assertThat(large).isEqualTo(small);
    }

    @Test
    void heartbeatSessionValidationUsesOneQueryRegardlessOfConnectedUsers() throws Exception {
        var users = new ArrayList<User>();
        try {
            User first = createUser("qcssefirst");
            users.add(first);
            notifications.subscribe(first);
            long small = queryCount(notifications::sendHeartbeat);
            for (int i = 0; i < 5; i++) {
                User user = createUser("qcsse" + i);
                users.add(user);
                notifications.subscribe(user);
            }
            long large = queryCount(notifications::sendHeartbeat);
            assertThat(small).isEqualTo(1);
            assertThat(large).isEqualTo(small);
        } finally {
            users.forEach(user -> notifications.revokeSessionsAfterCommit(user.getId()));
        }
    }

    @Test
    void subscriptionStorageQueryCountDoesNotScaleWithNotesOrSnapshots() throws Exception {
        User owner = createUser("qcstorage");
        long small = queryCount(() -> mockMvc.perform(get("/api/subscription").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));
        for (int i = 0; i < 3; i++) {
            Project project = seedPublishedProject(owner, "Storage " + i, 2, 2, null);
            mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                    .andExpect(status().isOk());
        }
        long large = queryCount(() -> mockMvc.perform(get("/api/subscription").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));
        assertThat(large).isEqualTo(small);
    }

    private Project seedPublishedProject(User owner, String title, int trails, int itemsPerTrail, User voter) throws Exception {
        Project project = createProject(owner, title, "published", "A description", "java,testing");
        for (int p = 0; p < trails; p++) {
            long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                    {"title":"Trail %d"}""".formatted(p));
            for (int i = 0; i < itemsPerTrail; i++) {
                postForId(owner, "/api/trail/" + trailId + "/item", """
                        {"title":"Item %d"}""".formatted(i));
            }
        }
        if (voter != null) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(voter)))
                    .andExpect(status().isOk());
        }
        publishService.publish(project.getId(), owner);
        return project;
    }

    @Test
    void projectStorageQueryCountDoesNotScaleWithNotes() throws Exception {
        User owner = createUser("qcprojectstorage");
        Project project = createProject(owner, "Storage", "private");
        String path = "/api/project/" + pid(project) + "/storage";
        long small = queryCount(() -> mockMvc.perform(get(path).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
        for (int i = 0; i < 5; i++) {
            postForId(owner, "/api/trail/" + trailId + "/item", "{\"title\":\"Note\"}");
        }
        long large = queryCount(() -> mockMvc.perform(get(path).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));
        assertThat(large).isEqualTo(small);
    }

    @Test
    void projectListGraphThumbnailQueryCountDoesNotScaleWithProjects() throws Exception {
        User owner = createUser("qcgraphowner");
        for (int i = 0; i < 2; i++) {
            Project project = seedPublishedProject(owner, "Graph " + i, 2, 1, null);
            mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                            .header("Authorization", bearer(owner))
                            .contentType("application/json")
                            .content("{\"type\":\"GRAPH\"}"))
                    .andExpect(status().isOk());
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/project")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 2; i < 8; i++) {
            Project project = seedPublishedProject(owner, "Graph " + i, 2, 1, null);
            mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                            .header("Authorization", bearer(owner))
                            .contentType("application/json")
                            .content("{\"type\":\"GRAPH\"}"))
                    .andExpect(status().isOk());
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void exploreSearchQueryCountDoesNotScaleWithPublishedProjects() throws Exception {
        User owner = createUser("qcsearchowner");
        for (int i = 0; i < 2; i++) {
            Project project = createProject(owner, "Search " + i, "private", "Description", "java");
            mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                    .andExpect(status().isOk());
        }
        long small = queryCount(() -> mockMvc.perform(get("/api/public/explore?q=search"))
                .andExpect(status().isOk()));
        for (int i = 2; i < 8; i++) {
            Project project = createProject(owner, "Search " + i, "private", "Description", "java");
            mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                    .andExpect(status().isOk());
        }
        long large = queryCount(() -> mockMvc.perform(get("/api/public/explore?q=search"))
                .andExpect(status().isOk()));
        assertThat(large).isEqualTo(small);
    }

    @Test
    void exploreBundleQueryCountDoesNotScaleWithFeedSize() throws Exception {
        User author1 = createUser("qcauthor1");
        User fan = createUser("qcfan");
        seedPublishedProject(author1, "Explore A", 1, 1, fan);
        seedPublishedProject(author1, "Explore B", 1, 1, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk()));

        for (int i = 0; i < 6; i++) {
            User author = createUser("qcauthor" + (i + 2));
            seedPublishedProject(author, "Explore " + i, 1, 2, fan);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void exploreBundleQueryCountDoesNotScaleForAuthenticatedUser() throws Exception {
        User author = createUser("qcauthor20");
        User viewer = createUser("qcviewer20");
        seedPublishedProject(author, "Authed A", 1, 1, viewer);
        seedPublishedProject(author, "Authed B", 1, 1, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/explore")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 6; i++) {
            seedPublishedProject(author, "Authed " + i, 1, 1, viewer);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/explore")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void exploreHotSortQueryCountDoesNotScaleWithFeedSize() throws Exception {
        User author1 = createUser("qchotauthor1");
        User fan = createUser("qchotfan");
        seedPublishedProject(author1, "Hot A", 1, 1, fan);
        seedPublishedProject(author1, "Hot B", 1, 1, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/explore?sort=hot"))
                .andExpect(status().isOk()));

        for (int i = 0; i < 6; i++) {
            User author = createUser("qchotauthor" + (i + 2));
            seedPublishedProject(author, "Hot " + i, 1, 2, fan);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/explore?sort=hot"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void explorePageTwoQueryCountDoesNotScaleWithTotalFeedSize() throws Exception {
        
        
        
        User author = createUser("qcpageauthor");
        User fan = createUser("qcpagefan");
        for (int i = 0; i < 22; i++) {
            seedPublishedProject(author, "Page " + i, 1, 1, fan);
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/public/explore?page=1&size=10"))
                .andExpect(status().isOk()));

        for (int i = 0; i < 8; i++) {
            seedPublishedProject(author, "Page extra " + i, 1, 1, fan);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/explore?page=1&size=10"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void explorePaginationCoversWholeFeedWithoutDuplicates() throws Exception {
        User author = createUser("qcpagcoverauthor");
        for (int i = 0; i < 12; i++) {
            seedPublishedProject(author, "Cover " + i, 1, 1, null);
        }

        String firstPageJson = mockMvc.perform(get("/api/public/explore?page=0&size=10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondPageJson = mockMvc.perform(get("/api/public/explore?page=1&size=10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode firstFeed = mapper.readTree(firstPageJson).get("feed");
        JsonNode secondFeed = mapper.readTree(secondPageJson).get("feed");

        assertThat(mapper.readTree(firstPageJson).get("hasMore").asBoolean()).isTrue();
        assertThat(mapper.readTree(secondPageJson).get("hasMore").asBoolean()).isFalse();
        assertThat(firstFeed).hasSize(10);
        assertThat(secondFeed).hasSize(2);

        Set<String> firstIds = new HashSet<>();
        firstFeed.forEach(node -> firstIds.add(node.get("id").asText()));
        Set<String> secondIds = new HashSet<>();
        secondFeed.forEach(node -> secondIds.add(node.get("id").asText()));

        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(firstIds).hasSize(10);
        assertThat(secondIds).hasSize(2);
    }

    @Test
    void publicProjectQueryCountDoesNotScaleWithContentSize() throws Exception {
        User owner = createUser("qcowner");
        Project smallProject = seedPublishedProject(owner, "Small", 1, 1, null);
        Project largeProject = seedPublishedProject(owner, "Large", 3, 4, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/project/" + pid(smallProject))
                        .header("X-Anon-Id", "qc-anon-1"))
                .andExpect(status().isOk()));

        long large = queryCount(() -> mockMvc.perform(get("/api/public/project/" + pid(largeProject))
                        .header("X-Anon-Id", "qc-anon-2"))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void publicProfileQueryCountDoesNotScaleWithPublishedProjects() throws Exception {
        User author = createUser("qcprofileauthor");
        User fan = createUser("qcprofilefan");
        seedPublishedProject(author, "Profile A", 1, 1, fan);
        seedPublishedProject(author, "Profile B", 1, 1, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/users/" + author.getUsername()))
                .andExpect(status().isOk()));

        for (int i = 0; i < 5; i++) {
            seedPublishedProject(author, "Profile " + i, 1, 1, fan);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/public/users/" + author.getUsername()))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void profileStatsQueryCountDoesNotScaleWithActivity() throws Exception {
        User me = createUser("qcbundleuser");
        User other = createUser("qcbundleother");

        seedPublishedProject(me, "Mine A", 1, 1, other);
        Project theirsFirst = seedPublishedProject(other, "Theirs A", 1, 1, null);
        mockMvc.perform(post("/api/project/" + pid(theirsFirst) + "/vote").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(theirsFirst) + "/bookmark").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        postForProjectId(me, "/api/project/" + pid(theirsFirst) + "/fork", "");

        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            seedPublishedProject(me, "Mine " + i, 1, 1, other);
            Project theirs = seedPublishedProject(other, "Theirs " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + pid(theirs) + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/project/" + pid(theirs) + "/bookmark").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void profilePublishedPageQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("qcpubuser");
        for (int i = 0; i < 3; i++) {
            seedPublishedProject(me, "Pub " + i, 1, 1, null);
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/published?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            seedPublishedProject(me, "Pub more " + i, 1, 1, null);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/published?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void profileBookmarksPageQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("qcbookuser");
        User other = createUser("qcbookother");
        for (int i = 0; i < 2; i++) {
            Project p = seedPublishedProject(other, "Book " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + pid(p) + "/bookmark").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/bookmarks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Book more " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + pid(p) + "/bookmark").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/bookmarks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void profileUpvotedPageQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("qcupvuser");
        User other = createUser("qcupvother");
        for (int i = 0; i < 2; i++) {
            Project p = seedPublishedProject(other, "Upv " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + pid(p) + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/upvoted?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Upv more " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + pid(p) + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/upvoted?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void profileForksPageQueryCountDoesNotScaleWithPageSize() throws Exception {
        User me = createUser("qcforkuser");
        User other = createUser("qcforkother");
        for (int i = 0; i < 2; i++) {
            Project p = seedPublishedProject(other, "Fork " + i, 1, 1, null);
            postForProjectId(me, "/api/project/" + pid(p) + "/fork", "");
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/forks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Fork more " + i, 1, 1, null);
            postForProjectId(me, "/api/project/" + pid(p) + "/fork", "");
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/forks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }
    @Test
    void moderationPagesDoNotAddQueriesForMoreReportsOrLogEntries() throws Exception {
        var admin = createAdmin("qcmodadmin"); var owner = createUser("qcmodowner"); var reporter = createUser("qcmodreporter");
        var project = createProject(owner, "Moderated", "published");
        for (int index = 0; index < 12; index++) {
            jdbcTemplate.update("INSERT INTO project_report (id, project_id, reporter_id, reason, status, created_date) VALUES (nextval('project_report_seq'), ?, ?, 'Review', 'OPEN', now())", project.getId(), reporter.getId());
            jdbcTemplate.update("INSERT INTO moderation_log (id, admin_id, action, target_type, target_id, created_date) VALUES (nextval('moderation_log_seq'), ?, 'UNPUBLISH_PROJECT', 'PROJECT', ?, now())", admin.getId(), project.getId());
        }
        for (String path : java.util.List.of("/api/admin/reports", "/api/admin/moderation-log")) {
            long small = queryCount(() -> mockMvc.perform(get(path).param("size", "1").header("Authorization", bearer(admin))).andExpect(status().isOk()));
            long large = queryCount(() -> mockMvc.perform(get(path).param("size", "20").header("Authorization", bearer(admin))).andExpect(status().isOk()));
            assertThat(large).isEqualTo(small);
        }
    }

    @Test
    void focusedCommentPageQueriesDoNotScaleWithRootPageSize() throws Exception {
        var owner = createUser("qcfocusowner"); var project = createProject(owner, "Focused comments", "published");
        long commentId = 0;
        for (int index = 0; index < 12; index++) commentId = postForId(owner, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Comment\"}");
        String path = "/api/public/project/" + pid(project) + "/comments?focusId=" + commentId;
        long small = queryCount(() -> mockMvc.perform(get(path).param("size", "1")).andExpect(status().isOk()));
        long large = queryCount(() -> mockMvc.perform(get(path).param("size", "20")).andExpect(status().isOk()));
        assertThat(large).isLessThanOrEqualTo(small);
    }

}
