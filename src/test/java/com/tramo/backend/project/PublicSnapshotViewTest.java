// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.PublicProjectService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicSnapshotViewTest extends AbstractIntegrationTest {
    @Autowired
    private PublicProjectService publicProjects;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void setContent(User owner, long itemId, String content) throws Exception {
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentRequest(lexicalText(content))))
                .andExpect(status().isNoContent());
    }

    private void publish(User owner, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    private Project publishedProjectWithContent(User owner, String title) throws Exception {
        Project project = createProject(owner, title, "private", "A description", "java");
        long trailId = createTrail(owner, project, "Chapter one");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        setContent(owner, itemA, "body of A");
        publish(owner, project);
        return project;
    }

    @Test
    void publicViewOfPublishedProjectIsServedFromTheSnapshot() throws Exception {
        User owner = createUser("psvowner1");
        Project project = publishedProjectWithContent(owner, "Snapshotted");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Snapshotted"))
                .andExpect(jsonPath("$.ownerUsername").value("psvowner1"))
                .andExpect(jsonPath("$.trails.length()").value(1))
                .andExpect(jsonPath("$.trails[0].title").value("Chapter one"))
                .andExpect(jsonPath("$.trails[0].items.length()").value(2))
                .andExpect(jsonPath("$.trails[0].items[0].title").value("A"))
                .andExpect(jsonPath("$.trails[0].items[0].content").value(lexicalText("body of A")));
    }

    @Test
    void snapshotViewKeepsShowingPublishedContentAfterALiveEdit() throws Exception {
        User owner = createUser("psvowner4");
        Project project = createProject(owner, "Frozen", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");
        long itemId = createItem(owner, trailId, "Item");
        setContent(owner, itemId, "published body");
        publish(owner, project);
        setContent(owner, itemId, "draft body");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trails[0].items[0].content").value(lexicalText("published body")));
    }

    @Test
    void anonymousViewsAreCountedOncePerAnonId() throws Exception {
        User owner = createUser("psvowner5");
        Project project = publishedProjectWithContent(owner, "Counted");

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(1));

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(1));

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(2));
    }

    @Test
    void authenticatedViewsAreCountedOncePerUser() throws Exception {
        User owner = createUser("authenticatedviewowner");
        User viewer = createUser("authenticatedviewer");
        Project project = publishedProjectWithContent(owner, "Counted");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.viewCount").value(1));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentViewsFromTheSameVisitorSucceedAndAreCountedOnce(boolean authenticated) throws Exception {
        User owner = createUser("concurrentviewowner");
        User viewer = authenticated ? createUser("concurrentviewer") : null;
        Project project = publishedProjectWithContent(owner, "Concurrent views");
        var inserted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var request = get("/api/public/project/" + pid(project)).header("X-Anon-Id", "same-visitor");
        if (authenticated) request.header("Authorization", bearer(viewer));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                publicProjects.getPublicProject(project.getId(), viewer, "same-visitor");
                inserted.countDown();
                release.orTimeout(10, TimeUnit.SECONDS).join();
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return mockMvc.perform(request);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.complete(null);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
        } finally {
            release.complete(null);
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project_view WHERE project_id = ?",
                Long.class, project.getId())).isEqualTo(1L);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getViewCount()).isEqualTo(1L);
    }

    @Test
    void viewsWithoutAnyViewerKeyAreNotCounted() throws Exception {
        User owner = createUser("psvowner6");
        Project project = publishedProjectWithContent(owner, "Uncounted");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(0));

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(0));
    }

    @Test
    void publicViewExposesForkSourceAndPermissions() throws Exception {
        User owner = createUser("psvowner7");
        User forker = createUser("psvforker7");
        Project source = publishedProjectWithContent(owner, "Source");
        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        Project fork = projectRepository.findById(projectIdCodec.decode(forkId)).orElseThrow();
        mockMvc.perform(post("/api/project/" + pid(fork) + "/publish").header("Authorization", bearer(forker)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + forkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forkedFromProjectId").value(pid(source)))
                .andExpect(jsonPath("$.forkedFromOwnerUsername").value("psvowner7"))
                .andExpect(jsonPath("$.canFork").value(true))
                .andExpect(jsonPath("$.canComment").value(false));
    }

    @Test
    void unpublishedProjectIsNotPubliclyVisible() throws Exception {
        User owner = createUser("psvowner8");
        Project project = createProject(owner, "Hidden", "private", "A description", null);

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isNotFound());
    }
}
