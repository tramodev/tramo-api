package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlockVisibilityTest extends AbstractIntegrationTest {

    private void block(User blocker, String target) throws Exception {
        mockMvc.perform(post("/api/users/" + target + "/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk());
    }

    @Test
    void theBlockedUserLosesAccessToEveryPublicSurfaceOfTheBlocker() throws Exception {
        var blocker = createUser("blockhideowner");
        var blocked = createUser("blockhideviewer");
        Project project = createProject(blocker, "Hidden from you", "published", "desc", null);
        block(blocker, "blockhideviewer");

        for (String path : new String[]{"", "/published", "/upvoted", "/followers", "/following"}) {
            mockMvc.perform(get("/api/public/users/blockhideowner" + path)
                            .header("Authorization", bearer(blocked)))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(blocked)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(blocked)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theBlockerKeepsSeeingTheBlockedProfileSoUnblockStaysReachable() throws Exception {
        var blocker = createUser("blockkeepowner");
        createUser("blockkeeptarget");
        block(blocker, "blockkeeptarget");

        mockMvc.perform(get("/api/public/users/blockkeeptarget").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));
    }

    @Test
    void everyoneElseStillSeesTheBlockersContent() throws Exception {
        var blocker = createUser("blockthirdowner");
        createUser("blockthirdblocked");
        var stranger = createUser("blockthirdstranger");
        Project project = createProject(blocker, "Still public", "published", "desc", null);
        block(blocker, "blockthirdblocked");

        mockMvc.perform(get("/api/public/users/blockthirdowner").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk());
    }

    @Test
    void blockedProjectsDisappearFromExploreForBothParties() throws Exception {
        var blocker = createUser("blockfeedowner");
        var blocked = createUser("blockfeedviewer");
        createProject(blocker, "Owner post", "published", "desc", null);
        createProject(blocked, "Viewer post", "published", "desc", null);

        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(2));

        block(blocker, "blockfeedviewer");

        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1))
                .andExpect(jsonPath("$.feed[0].title").value("Viewer post"));

        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1))
                .andExpect(jsonPath("$.feed[0].title").value("Owner post"));

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(2));
    }

    @Test
    void searchAndHotSortAlsoRespectTheBlock() throws Exception {
        var blocker = createUser("blocksearchowner");
        var blocked = createUser("blocksearchviewer");
        createProject(blocker, "Findable thing", "published", "desc", null);
        block(blocker, "blocksearchviewer");

        mockMvc.perform(get("/api/public/explore?q=Findable").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0));
        mockMvc.perform(get("/api/public/explore?sort=hot").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0));
    }

    @Test
    void blockedAuthorsVanishFromAThirdPartysCommentThread() throws Exception {
        var host = createUser("blockcmthost");
        var blocker = createUser("blockcmtblocker");
        var noisy = createUser("blockcmtnoisy");
        Project project = createProject(host, "Shared thread", "published", "desc", null);

        for (User author : new User[]{blocker, noisy}) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                            .header("Authorization", bearer(author))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"hello"}"""))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        block(blocker, "blockcmtnoisy");

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(blocker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].authorUsername").value("blockcmtblocker"));
    }

    @Test
    void unblockingRestoresEverything() throws Exception {
        var blocker = createUser("blockundoowner");
        var blocked = createUser("blockundoviewer");
        Project project = createProject(blocker, "Comes back", "published", "desc", null);

        block(blocker, "blockundoviewer");
        mockMvc.perform(get("/api/public/users/blockundoowner").header("Authorization", bearer(blocked)))
                .andExpect(status().isNotFound());

        block(blocker, "blockundoviewer");

        mockMvc.perform(get("/api/public/users/blockundoowner").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(blocked)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(blocked)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1));
    }
}
