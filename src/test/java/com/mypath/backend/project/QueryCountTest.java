package com.mypath.backend.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueryCountTest extends AbstractIntegrationTest {

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @FunctionalInterface
    interface HttpCall {
        void run() throws Exception;
    }

    private long queryCount(HttpCall call) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    private Project seedPublishedProject(User owner, String title, int paths, int ideasPerPath, User voter) throws Exception {
        Project project = createProject(owner, title, "published", "A description", "java,testing");
        for (int p = 0; p < paths; p++) {
            long pathId = postForId(owner, "/api/project/" + project.getId() + "/path", """
                    {"title":"Path %d"}""".formatted(p));
            for (int i = 0; i < ideasPerPath; i++) {
                postForId(owner, "/api/path/" + pathId + "/idea", """
                        {"title":"Idea %d"}""".formatted(i));
            }
        }
        if (voter != null) {
            mockMvc.perform(post("/api/project/" + project.getId() + "/vote").header("Authorization", bearer(voter)))
                    .andExpect(status().isOk());
        }
        return project;
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
        // Seed enough that page 1 is a full page of 10 in both runs (a short final page lets
        // Spring Data infer "last page" and skip the count query, which would make the query
        // count differ for a reason unrelated to scaling — keep both runs on the same code path).
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

        Set<Long> firstIds = new HashSet<>();
        firstFeed.forEach(node -> firstIds.add(node.get("id").asLong()));
        Set<Long> secondIds = new HashSet<>();
        secondFeed.forEach(node -> secondIds.add(node.get("id").asLong()));

        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(firstIds).hasSize(10);
        assertThat(secondIds).hasSize(2);
    }

    @Test
    void publicProjectQueryCountDoesNotScaleWithContentSize() throws Exception {
        User owner = createUser("qcowner");
        Project smallProject = seedPublishedProject(owner, "Small", 1, 1, null);
        Project largeProject = seedPublishedProject(owner, "Large", 3, 4, null);

        long small = queryCount(() -> mockMvc.perform(get("/api/public/project/" + smallProject.getId())
                        .header("X-Anon-Id", "qc-anon-1"))
                .andExpect(status().isOk()));

        long large = queryCount(() -> mockMvc.perform(get("/api/public/project/" + largeProject.getId())
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
        mockMvc.perform(post("/api/project/" + theirsFirst.getId() + "/vote").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + theirsFirst.getId() + "/bookmark").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        postForId(me, "/api/project/" + theirsFirst.getId() + "/fork", "");

        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            seedPublishedProject(me, "Mine " + i, 1, 1, other);
            Project theirs = seedPublishedProject(other, "Theirs " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + theirs.getId() + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/project/" + theirs.getId() + "/bookmark").header("Authorization", bearer(me)))
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
            mockMvc.perform(post("/api/project/" + p.getId() + "/bookmark").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/bookmarks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Book more " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + p.getId() + "/bookmark").header("Authorization", bearer(me)))
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
            mockMvc.perform(post("/api/project/" + p.getId() + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/upvoted?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Upv more " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + p.getId() + "/vote").header("Authorization", bearer(me)))
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
            postForId(me, "/api/project/" + p.getId() + "/fork", "");
        }

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/forks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            Project p = seedPublishedProject(other, "Fork more " + i, 1, 1, null);
            postForId(me, "/api/project/" + p.getId() + "/fork", "");
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/forks?page=0&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }
}
