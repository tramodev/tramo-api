package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlockedUserTest extends AbstractIntegrationTest {

    @Test
    void toggleBlockThenUnblock() throws Exception {
        var blocker = createUser("blocktoggler");
        createUser("blocktarget");

        mockMvc.perform(post("/api/users/blocktarget/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));

        mockMvc.perform(get("/api/users/blocked").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("blocktarget"));

        mockMvc.perform(post("/api/users/blocktarget/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));

        mockMvc.perform(get("/api/users/blocked").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void cannotBlockSelf() throws Exception {
        var user = createUser("blockself");

        mockMvc.perform(post("/api/users/blockself/block").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void blockingRemovesExistingFollowInBothDirections() throws Exception {
        var alice = createUser("blockfollowalice");
        var bob = createUser("blockfollowbob");

        mockMvc.perform(post("/api/users/blockfollowbob/follow").header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/blockfollowalice/follow").header("Authorization", bearer(bob)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/blockfollowbob/block").header("Authorization", bearer(alice)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/users/blockfollowbob").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.blocked").value(true));

        mockMvc.perform(get("/api/public/users/blockfollowalice").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void blockedUserCannotFollowCommentOrFork() throws Exception {
        var owner = createUser("blockedguardowner");
        var blocked = createUser("blockedguardtarget");
        Project project = createProject(owner, "Guarded", "published", "A description", null);

        mockMvc.perform(post("/api/users/blockedguardtarget/block").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/blockedguardowner/follow").header("Authorization", bearer(blocked)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(blocked))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Let me in"}"""))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(blocked)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBlockedUsersIsPaginated() throws Exception {
        var blocker = createUser("blockpageowner");
        createUser("blockpagea");
        createUser("blockpageb");

        mockMvc.perform(post("/api/users/blockpagea/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/blockpageb/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/blocked?page=0&size=1").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));

        mockMvc.perform(get("/api/users/blocked?page=1&size=1").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }
}
