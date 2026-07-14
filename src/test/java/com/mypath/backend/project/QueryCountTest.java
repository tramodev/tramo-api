package com.mypath.backend.project;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    void profileBundleQueryCountDoesNotScaleWithActivity() throws Exception {
        User me = createUser("qcbundleuser");
        User other = createUser("qcbundleother");

        seedPublishedProject(me, "Mine A", 1, 1, other);
        Project theirsFirst = seedPublishedProject(other, "Theirs A", 1, 1, null);
        mockMvc.perform(post("/api/project/" + theirsFirst.getId() + "/vote").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + theirsFirst.getId() + "/bookmark").header("Authorization", bearer(me)))
                .andExpect(status().isOk());
        postForId(me, "/api/project/" + theirsFirst.getId() + "/fork", "");

        mockMvc.perform(get("/api/profile/bundle").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        long small = queryCount(() -> mockMvc.perform(get("/api/profile/bundle").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 4; i++) {
            seedPublishedProject(me, "Mine " + i, 1, 1, other);
            Project theirs = seedPublishedProject(other, "Theirs " + i, 1, 1, null);
            mockMvc.perform(post("/api/project/" + theirs.getId() + "/vote").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/project/" + theirs.getId() + "/bookmark").header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/profile/bundle").header("Authorization", bearer(me)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }
}
