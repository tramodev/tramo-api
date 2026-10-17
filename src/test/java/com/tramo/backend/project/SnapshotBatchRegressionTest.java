package com.tramo.backend.project;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.project.service.ExploreService;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.project.snapshot.SnapshotProcessing;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"app.snapshot.max-bytes=4096", "app.snapshot.processing-bytes=4096"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SnapshotBatchRegressionTest extends AbstractIntegrationTest {
    @Autowired ProjectPublishService publish;
    @Autowired ExploreService explore;
    @Autowired SnapshotProcessing processing;
    @MockitoSpyBean SnapshotLimits limits;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    private List<Project> seed(User owner, int count) throws Exception {
        List<Project> projects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Project project = createProject(owner, "Published " + i, "private", "Description", null);
            project.setThumbnailType(ProjectThumbnailType.GRAPH);
            projectRepository.save(project);
            long trail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Public trail\"}");
            long note = postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"Public note\"}");
            Long content = jdbcTemplate.queryForObject("select nextval('item_content_seq')", Long.class);
            jdbcTemplate.update("insert into item_content (id, content, extraction_epoch) values (?, ?, 0)",
                    content, "{\"root\":{\"children\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(240) + "\"}]}}");
            jdbcTemplate.update("update item set content_id = ? where id = ?", content, note);
            publish.publish(project.getId(), owner);
            jdbcTemplate.update("update project_snapshot set content = rpad(content, 3000, ' ') where project_id = ?", project.getId());
            jdbcTemplate.update("update project set last_published_date = timestamp '2020-01-01' + ? * interval '1 day', " +
                    "creation_date = timestamp '2020-01-01' + ? * interval '1 day' where id = ?", i, i, project.getId());
            projects.add(project);
        }
        return projects;
    }

    private String response(String path) throws Exception {
        return mockMvc.perform(get(path).with(uniqueIp())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void validSnapshotsOverBatchBudgetKeepOrderPaginationFeaturedAndTrending() throws Exception {
        User owner = createUser("batchpublic");
        var projects = seed(owner, 3);
        jdbcTemplate.update("update project set featured = true where id = ?", projects.get(0).getId());
        String first = response("/api/public/explore?size=2");
        assertThat(JsonPath.<List<String>>read(first, "$.feed[*].id"))
                .containsExactly(pid(projects.get(2)), pid(projects.get(1)));
        assertThat(JsonPath.<Boolean>read(first, "$.hasMore")).isTrue();
        assertThat(JsonPath.<String>read(first, "$.featured.title")).isEqualTo("Published 0");
        String second = response("/api/public/explore?size=2&page=1");
        assertThat(JsonPath.<List<String>>read(second, "$.feed[*].id")).containsExactly(pid(projects.get(0)));
        assertThat(JsonPath.<Boolean>read(second, "$.hasMore")).isFalse();
        String profile = response("/api/public/users/batchpublic/published?size=2");
        assertThat(JsonPath.<List<String>>read(profile, "$.content[*].id"))
                .containsExactly(pid(projects.get(2)), pid(projects.get(1)));
        assertThat(JsonPath.<Boolean>read(profile, "$.hasMore")).isTrue();
        String profileSecond = response("/api/public/users/batchpublic/published?size=2&page=1");
        assertThat(JsonPath.<List<String>>read(profileSecond, "$.content[*].id")).containsExactly(pid(projects.get(0)));
        assertThat(JsonPath.<Boolean>read(profileSecond, "$.hasMore")).isFalse();
        assertThat(explore.getTrendingProjects(5)).hasSize(3);
        explore.refreshExploreCache();
        assertThat(JsonPath.<List<?>>read(response("/api/public/explore"), "$.trendingProjects")).hasSize(3);
    }

    @Test
    void excessiveLegacySnapshotDoesNotInvalidateOtherProjectsOrCacheRefresh() throws Exception {
        User owner = createUser("batchlegacy");
        var projects = seed(owner, 3);
        jdbcTemplate.update("update project_snapshot set content = repeat(' ', 5000) where project_id = ?", projects.get(1).getId());
        jdbcTemplate.update("update project set featured = true where id = ?", projects.get(1).getId());
        clearInvocations(limits);
        String first = response("/api/public/explore?size=2");
        verify(limits, atLeastOnce()).readPreview(isNull());
        assertThat(JsonPath.<Object>read(first, "$.featured")).isNull();
        assertThat(JsonPath.<List<String>>read(first, "$.feed[*].id")).containsExactly(pid(projects.get(2)));
        assertThat(JsonPath.<Boolean>read(first, "$.hasMore")).isTrue();
        String second = response("/api/public/explore?size=2&page=1");
        assertThat(JsonPath.<List<String>>read(second, "$.feed[*].id")).containsExactly(pid(projects.get(0)));
        assertThat(JsonPath.<Boolean>read(second, "$.hasMore")).isFalse();
        assertThat(JsonPath.<List<String>>read(response("/api/public/users/batchlegacy/published"), "$.content[*].id"))
                .containsExactly(pid(projects.get(2)), pid(projects.get(0)));
        explore.refreshExploreCache();
        assertThat(JsonPath.<List<?>>read(response("/api/public/explore"), "$.trendingProjects")).hasSize(2);
    }

    @Test
    void validOwnerPreviewsDoNotDisappearWhenTheirCombinedBodiesExceedBudget() throws Exception {
        User owner = createUser("batchpreviews");
        seed(owner, 10);
        String body = mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<?>>read(body, "$[*].thumbnailGraph")).hasSize(10).doesNotContainNull();
        jdbcTemplate.update("update item_content set content = repeat('x', 3000) where id = (select min(content_id) from item)");
        String excessive = mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<?>>read(excessive, "$[*].thumbnailGraph")).filteredOn(graph -> graph != null).hasSize(9);
    }

    @Test
    void cursorUsesBoundedFetchesAndDoesNotAccumulateSnapshotEntities() throws Exception {
        User owner = createUser("batchcursor");
        seed(owner, 3);
        var bytes = new ArrayList<Integer>();
        processing.read("select content from project_snapshot order by id", Map.of(), rows -> {
            assertThat(rows.getStatement().getConnection().getAutoCommit()).isFalse();
            assertThat(rows.getType()).isEqualTo(java.sql.ResultSet.TYPE_FORWARD_ONLY);
            assertThat(rows.getStatement().getFetchSize()).isEqualTo(1);
            assertThat((long) rows.getStatement().getFetchSize() * limits.maxBytes()).isLessThanOrEqualTo(4096);
            bytes.add(rows.getString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        });
        assertThat(bytes).containsExactly(3000, 3000, 3000);
        assertThat(bytes.stream().mapToInt(Integer::intValue).sum()).isGreaterThan(4096);
        processing.forEachLatestPublish(jdbcTemplate.queryForList("select id from project", Long.class), (id, data) -> {
            assertThat(entityManager.unwrap(org.hibernate.Session.class).getStatistics().getEntityCount()).isZero();
        });
    }

    @Test
    void batchProcessingNeverCompletesPublicDataFromDrafts() throws Exception {
        User owner = createUser("batchprivacy");
        var projects = seed(owner, 3);
        for (Project project : projects) {
            long trail = jdbcTemplate.queryForObject("select id from trail where project_id = ?", Long.class, project.getId());
            long target = postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"DRAFT NOTE\"}");
            jdbcTemplate.update("update item_content set content = ? where id in " +
                    "(select content_id from item where project_id = ?)",
                    "{\"root\":{\"children\":[{\"type\":\"link\",\"rel\":\"tramo-idea:" + target + "\"}]}}", project.getId());
            jdbcTemplate.update("update trail set title = 'DRAFT TRAIL' where project_id = ?", project.getId());
            jdbcTemplate.update("update project set title = 'DRAFT PROJECT', thumbnail_type = 'DEDICATED', " +
                    "thumbnail_image_url = 'https://example.com/secret.png' where id = ?", project.getId());
        }
        explore.refreshExploreCache();
        for (String path : List.of("/api/public/explore", "/api/public/users/batchprivacy/published")) {
            assertThat(response(path)).contains("Published 0", "Published 1", "Published 2", "Public trail", "Public note")
                    .doesNotContain("DRAFT", "secret.png");
        }
        String feed = response("/api/public/explore");
        assertThat(JsonPath.<List<?>>read(feed, "$.feed[*].thumbnailGraph.items[*].linkedItemIds[*]")).isEmpty();
    }

    @Test
    void malformedRecordDoesNotAbortTrendingCacheUpdate() throws Exception {
        User owner = createUser("batchcorrupt");
        var projects = seed(owner, 3);
        explore.refreshExploreCache();
        jdbcTemplate.update("update project_snapshot set content = '{' where project_id = ?", projects.get(1).getId());
        jdbcTemplate.update("update project_snapshot set content = replace(content, 'Published 2', 'Refreshed') where project_id = ?",
                projects.get(2).getId());
        explore.refreshExploreCache();
        String body = response("/api/public/explore");
        assertThat(JsonPath.<List<String>>read(body, "$.trendingProjects[*].title"))
                .containsExactlyInAnyOrder("Published 0", "Refreshed");
        assertThat(JsonPath.<List<String>>read(body, "$.feed[*].title")).containsExactly("Refreshed", "Published 0");
    }
}
