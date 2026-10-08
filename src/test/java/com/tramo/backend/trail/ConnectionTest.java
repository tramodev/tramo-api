package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConnectionTest extends AbstractIntegrationTest {
    @Autowired ItemService items;

    private long note(User user, Project project, String title) throws Exception {
        return postForId(user, "/api/project/" + pid(project) + "/item", "{\"title\":\"" + title + "\"}");
    }

    @Test
    void createEditDeleteKeepsDirectionIdentityAndContent() throws Exception {
        User user = createUser("connections");
        Project project = createProject(user, "Notes", "private");
        long a = note(user, project, "A"), b = note(user, project, "B");
        var ab = items.tie(a, b, "  Context  ", user);
        var ba = items.tie(b, a, "", user);
        assertThat(ab.id()).isNotEqualTo(ba.id());
        assertThat(ab.text()).isEqualTo("Context");
        assertThat(ba.text()).isNull();
        mockMvc.perform(put("/api/item/" + a + "/association/" + ab.id()).header("Authorization", bearer(user))
                .contentType("application/json").content("{\"text\":\"Updated\",\"targetId\":" + a + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ab.id()))
                .andExpect(jsonPath("$.targetId").value(String.valueOf(b))).andExpect(jsonPath("$.text").value("Updated"));
        assertThat(items.getAssociations(a, user)).singleElement().satisfies(tie -> assertThat(tie.text()).isEqualTo("Updated"));
        items.updateAssociation(a, Long.valueOf(ab.id()), " \n ", user);
        assertThat(items.getAssociations(a, user).get(0).text()).isNull();
        mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(user)).contentType("application/json")
                .content("{\"targetId\":" + b + "}" )).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/item/" + a + "/tie").header("Authorization", bearer(user)).contentType("application/json")
                .content("{\"targetId\":" + a + "}" )).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/item/" + a + "/association/" + ab.id()).header("Authorization", bearer(user))
                .contentType("application/json").content("{\"text\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(items.getContent(a, user).getContent()).isEmpty();
        items.untie(a, Long.valueOf(ab.id()), user);
        assertThat(items.getAssociations(a, user)).isEmpty();
        assertThat(items.getAssociations(b, user)).hasSize(1);
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
        items.tie(c, a, null, user);
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
        mockMvc.perform(put("/api/item/" + b + "/association/" + ab.id()).header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"text\":\"No\"}" )).andExpect(status().isNotFound());
        assertThat(items.getAssociations(a, owner)).containsExactly(ab);
    }

    @Test
    void databaseEnforcesDirectedPairsProjectsReferencesAndCascade() throws Exception {
        User user = createUser("conndb");
        Project project = createProject(user, "Notes", "private"), another = createProject(user, "Other", "private");
        long a = note(user, project, "A"), b = note(user, project, "B"), c = note(user, another, "C");
        items.tie(a, b, null, user); items.tie(b, a, null, user);
        String sql = "insert into association(id, source_item_id, target_id, project_id) values (-1, ?, ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, b, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, a, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, c, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, a, -1, project.getId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("delete from item where id = ?", b);
        assertThat(jdbcTemplate.queryForObject("select count(*) from association", Long.class)).isZero();
        assertThat(items.getItemsForProject(project.getId(), user)).hasSize(1);
    }
}
