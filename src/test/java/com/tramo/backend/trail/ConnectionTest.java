package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConnectionTest extends AbstractIntegrationTest {
    @Autowired ItemService items;

    private long note(User user, Project project, String title) throws Exception {
        return postForId(user, "/api/project/" + pid(project) + "/item", "{\"title\":\"" + title + "\"}");
    }

    @Test
    void createEditDeleteWorksFromEitherNote() throws Exception {
        User user = createUser("connections");
        Project project = createProject(user, "Notes", "private");
        long a = note(user, project, "A"), b = note(user, project, "B");
        var ab = items.tie(a, b, "  Context  ", user);
        assertThat(ab.text()).isEqualTo("Context");
        assertThat(items.getAssociations(a, user)).singleElement().satisfies(tie -> {
            assertThat(tie.id()).isEqualTo(ab.id());
            assertThat(tie.targetId()).isEqualTo(String.valueOf(b));
        });
        assertThat(items.getAssociations(b, user)).singleElement().satisfies(tie -> {
            assertThat(tie.id()).isEqualTo(ab.id());
            assertThat(tie.targetId()).isEqualTo(String.valueOf(a));
            assertThat(tie.targetTitle()).isEqualTo("A");
        });
        mockMvc.perform(put("/api/item/" + b + "/association/" + ab.id()).header("Authorization", bearer(user))
                .contentType("application/json").content("{\"text\":\"Updated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ab.id()))
                .andExpect(jsonPath("$.targetId").value(String.valueOf(a))).andExpect(jsonPath("$.text").value("Updated"));
        assertThat(items.getAssociations(a, user).get(0).text()).isEqualTo("Updated");
        items.updateAssociation(a, Long.valueOf(ab.id()), " \n ", user);
        assertThat(items.getAssociations(b, user).get(0).text()).isNull();
        mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(user)).contentType("application/json")
                .content("{\"targetId\":" + b + "}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/item/" + b + "/tie").header("Authorization", bearer(user)).contentType("application/json")
                .content("{\"targetId\":" + a + "}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(user)).contentType("application/json")
                .content("{\"targetId\":" + a + "}")).andExpect(status().isBadRequest());
        items.updateAssociation(b, Long.valueOf(ab.id()), "x".repeat(4002), user);
        mockMvc.perform(put("/api/item/" + a + "/association/" + ab.id()).header("Authorization", bearer(user))
                .contentType("application/json").content("{\"text\":\"" + "x".repeat(4003) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(items.getContent(a, user).getContent()).isEmpty();
        items.untie(b, Long.valueOf(ab.id()), user);
        assertThat(items.getAssociations(a, user)).isEmpty();
        assertThat(items.getAssociations(b, user)).isEmpty();
    }

    @Test
    void connectionsSurviveTrailChangesAndAreRemovedOnlyWithNotes() throws Exception {
        User user = createUser("connectiontrail");
        Project project = createProject(user, "Notes", "private");
        long a = note(user, project, "A"), b = note(user, project, "B"), c = note(user, project, "C");
        long first = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"First\"}");
        long second = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Second\"}");
        items.attachToTrail(first, a, user); items.attachToTrail(first, b, user); items.attachToTrail(first, c, user);
        items.attachToTrail(second, c, user);
        assertThat(items.getAssociations(a, user)).isEmpty();
        var ac = items.tie(a, c, "Shared text", user);
        assertThat(items.getAssociations(c, user)).singleElement().satisfies(tie -> assertThat(tie.targetId()).isEqualTo(String.valueOf(a)));
        items.reorderTrailItems(first, java.util.List.of(c, b, a), user);
        items.detachFromTrail(first, c, user);
        items.detachFromTrail(second, c, user);
        assertThat(items.getAssociations(a, user)).containsExactly(ac);
        items.delete(c, user);
        assertThat(items.getAssociations(a, user)).isEmpty();
        assertThat(items.getItemsForProject(project.getId(), user)).hasSize(2);
        assertThat(items.getContent(b, user)).isNotNull();
    }

    @Test
    void authorizationAndProjectBoundaryApplyToEveryMutation() throws Exception {
        User owner = createUser("connowner"), other = createUser("connother");
        Project mine = createProject(owner, "Mine", "private"), another = createProject(owner, "Another", "private");
        Project theirs = createProject(other, "Theirs", "private");
        long a = note(owner, mine, "A"), b = note(owner, mine, "B"), c = note(owner, another, "C"), d = note(other, theirs, "D");
        var ab = items.tie(a, b, null, owner);
        for (long target : new long[]{c, d}) {
            mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(owner))
                    .contentType("application/json").content("{\"targetId\":" + target + "}"))
                    .andExpect(target == c ? status().isBadRequest() : status().isForbidden());
        }
        mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(other))
                .contentType("application/json").content("{\"targetId\":" + b + "}" )).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/item/" + a + "/association/" + ab.id()).header("Authorization", bearer(other))
                .contentType("application/json").content("{\"text\":\"No\"}" )).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/item/" + a + "/association/" + ab.id()).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/item/" + c + "/association/" + ab.id()).header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"text\":\"No\"}" )).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/item/" + b + "/association/" + ab.id()).header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"text\":\"Allowed\"}" )).andExpect(status().isOk());
        assertThat(items.getAssociations(a, owner).get(0).text()).isEqualTo("Allowed");
    }

    @Test
    void databaseEnforcesUniqueUnorderedPairsProjectsReferencesAndCascade() throws Exception {
        User user = createUser("conndb");
        Project project = createProject(user, "Notes", "private"), another = createProject(user, "Other", "private");
        long a = note(user, project, "A"), b = note(user, project, "B"), c = note(user, another, "C");
        items.tie(b, a, null, user);
        String sql = "insert into association(id, source_item_id, target_id, project_id) values (-1, ?, ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, b, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, b, a, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, a, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, c, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, -1, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("delete from item where id = ?", b);
        assertThat(jdbcTemplate.queryForObject("select count(*) from association", Long.class)).isZero();
        assertThat(items.getItemsForProject(project.getId(), user)).hasSize(1);
    }

    @Test
    void migrationMergesReverseConnectionsWithoutLosingEitherText() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE association (id bigint PRIMARY KEY, source_item_id bigint NOT NULL, target_id bigint NOT NULL, text varchar(2000), CONSTRAINT uq_association_direction UNIQUE (source_item_id, target_id), CONSTRAINT ck_association_distinct CHECK (source_item_id <> target_id))");
                try {
                    statement.execute("INSERT INTO association VALUES (1, 1, 2, '" + "a".repeat(2000) + "'), (2, 2, 1, '" + "b".repeat(2000) + "')");
                    try (var scanner = new java.util.Scanner(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V29__undirected_connections.sql")), "UTF-8")) {
                        for (String sql : scanner.useDelimiter("\\A").next().split(";")) if (!sql.isBlank()) statement.execute(sql);
                    }
                    try (var rows = statement.executeQuery("SELECT source_item_id, target_id, text FROM association")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                        assertThat(rows.getLong(2)).isEqualTo(2);
                        assertThat(rows.getString(3)).isEqualTo("a".repeat(2000) + "\n\n" + "b".repeat(2000));
                        assertThat(rows.next()).isFalse();
                    }
                } finally {
                    statement.execute("DROP TABLE association");
                }
            }
            return null;
        });
    }
}
