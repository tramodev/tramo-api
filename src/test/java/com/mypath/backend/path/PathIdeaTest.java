package com.mypath.backend.path;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.path.repository.IdeaLinkRepository;
import com.mypath.backend.path.repository.IdeaRepository;
import com.mypath.backend.path.repository.PathIdeaRepository;
import com.mypath.backend.path.repository.PathRepository;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PathIdeaTest extends AbstractIntegrationTest {

    @Autowired
    IdeaRepository ideaRepository;

    @Autowired
    PathIdeaRepository pathIdeaRepository;

    @Autowired
    IdeaLinkRepository ideaLinkRepository;

    @Autowired
    PathRepository pathRepository;

    private long createPath(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + project.getId() + "/path", """
                {"title":"%s"}""".formatted(title));
    }

    private long createIdea(User owner, long pathId, String title) throws Exception {
        return postForId(owner, "/api/path/" + pathId + "/idea", """
                {"title":"%s"}""".formatted(title));
    }

    @Test
    void createPathUnderOwnProject() throws Exception {
        User owner = createUser("pathmaker");
        Project project = createProject(owner, "Container", "private");

        mockMvc.perform(post("/api/project/" + project.getId() + "/path")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"My path"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My path"))
                .andExpect(jsonPath("$.projectId").value(project.getId()));
    }

    @Test
    void createPathRequiresTitle() throws Exception {
        User owner = createUser("pathmaker2");
        Project project = createProject(owner, "Container2", "private");

        mockMvc.perform(post("/api/project/" + project.getId() + "/path")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPathUnderOthersProjectIsForbidden() throws Exception {
        User owner = createUser("pathowner");
        User intruder = createUser("pathintruder");
        Project project = createProject(owner, "NotYours", "private");

        mockMvc.perform(post("/api/project/" + project.getId() + "/path")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Sneaky"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void pathEndpointsEnforceOwnership() throws Exception {
        User owner = createUser("pathowner2");
        User intruder = createUser("pathintruder2");
        Project project = createProject(owner, "Guarded", "private");
        long pathId = createPath(owner, project, "Guarded path");

        mockMvc.perform(get("/api/path/" + pathId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/path/" + pathId)
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Hijacked"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/path/" + pathId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/project/" + project.getId() + "/path").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathIs404() throws Exception {
        User user = createUser("pathseeker");
        mockMvc.perform(get("/api/path/424242").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatePathChangesTitle() throws Exception {
        User owner = createUser("pathrenamer");
        Project project = createProject(owner, "Renaming", "private");
        long pathId = createPath(owner, project, "Before");

        mockMvc.perform(put("/api/path/" + pathId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"After"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("After"));
    }

    @Test
    void deletePathDeletesOrphanedIdeas() throws Exception {
        User owner = createUser("pathdeleter");
        Project project = createProject(owner, "Cleanup", "private");
        long pathId = createPath(owner, project, "Doomed");
        long ideaId = createIdea(owner, pathId, "Orphan-to-be");

        mockMvc.perform(delete("/api/path/" + pathId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(ideaRepository.findById(ideaId)).isEmpty();
    }

    @Test
    void sharedIdeaSurvivesSinglePathDelete() throws Exception {
        User owner = createUser("sharer");
        Project project = createProject(owner, "Shared", "private");
        long path1 = createPath(owner, project, "First");
        long path2 = createPath(owner, project, "Second");
        long ideaId = createIdea(owner, path1, "Shared idea");

        mockMvc.perform(post("/api/path/" + path2 + "/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/path/" + path1).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(ideaRepository.findById(ideaId)).isPresent();
        assertThat(pathIdeaRepository.findByIdeaId(ideaId)).hasSize(1);
    }

    @Test
    void createIdeaRequiresTitle() throws Exception {
        User owner = createUser("ideamaker");
        Project project = createProject(owner, "Ideas", "private");
        long pathId = createPath(owner, project, "Ideas path");

        mockMvc.perform(post("/api/path/" + pathId + "/idea")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ideaContentRoundtrip() throws Exception {
        User owner = createUser("writer");
        Project project = createProject(owner, "Writing", "private");
        long pathId = createPath(owner, project, "Writing path");
        long ideaId = createIdea(owner, pathId, "Draft");

        mockMvc.perform(get("/api/idea/" + ideaId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""));

        mockMvc.perform(put("/api/idea/" + ideaId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"{\\"root\\":{\\"children\\":[]}}"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/idea/" + ideaId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("{\"root\":{\"children\":[]}}"));
    }

    @Test
    void ideaEndpointsEnforceOwnership() throws Exception {
        User owner = createUser("ideaowner");
        User intruder = createUser("ideaintruder");
        Project project = createProject(owner, "Locked", "private");
        long pathId = createPath(owner, project, "Locked path");
        long ideaId = createIdea(owner, pathId, "Locked idea");

        mockMvc.perform(get("/api/idea/" + ideaId + "/content").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/idea/" + ideaId + "/content")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"stolen"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/idea/" + ideaId)
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stolen"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/idea/" + ideaId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateIdeaChangesTitleAndType() throws Exception {
        User owner = createUser("ideaeditor");
        Project project = createProject(owner, "Editing", "private");
        long pathId = createPath(owner, project, "Editing path");
        long ideaId = createIdea(owner, pathId, "Plain");

        mockMvc.perform(put("/api/idea/" + ideaId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed","type":"video"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.type").value("video"));
    }

    @Test
    void deleteIdeaRemovesIt() throws Exception {
        User owner = createUser("ideadeleter");
        Project project = createProject(owner, "Deleting", "private");
        long pathId = createPath(owner, project, "Deleting path");
        long ideaId = createIdea(owner, pathId, "Doomed idea");

        mockMvc.perform(delete("/api/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(ideaRepository.findById(ideaId)).isEmpty();
        assertThat(pathIdeaRepository.findByIdeaId(ideaId)).isEmpty();
    }

    @Test
    void attachIsIdempotentAndDetachDeletesOrphan() throws Exception {
        User owner = createUser("attacher");
        Project project = createProject(owner, "Attaching", "private");
        long path1 = createPath(owner, project, "P1");
        long path2 = createPath(owner, project, "P2");
        long ideaId = createIdea(owner, path1, "Movable");

        mockMvc.perform(post("/api/path/" + path2 + "/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/path/" + path2 + "/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(pathIdeaRepository.findByIdeaId(ideaId)).hasSize(2);

        mockMvc.perform(delete("/api/path/" + path1 + "/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(pathIdeaRepository.findByIdeaId(ideaId)).hasSize(1);
        assertThat(ideaRepository.findById(ideaId)).isPresent();

        mockMvc.perform(delete("/api/path/" + path2 + "/idea/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(ideaRepository.findById(ideaId)).isEmpty();
    }

    @Test
    void linkingIdeasIsSymmetricAndIdempotent() throws Exception {
        User owner = createUser("linker");
        Project project = createProject(owner, "Linking", "private");
        long pathId = createPath(owner, project, "Linking path");
        long ideaA = createIdea(owner, pathId, "A");
        long ideaB = createIdea(owner, pathId, "B");

        mockMvc.perform(post("/api/idea/" + ideaA + "/link/" + ideaB).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/idea/" + ideaA + "/link/" + ideaB).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/idea/" + ideaB + "/link/" + ideaA).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(ideaLinkRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/idea/" + ideaB + "/link").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("A"));
    }

    @Test
    void selfLinkIsRejected() throws Exception {
        User owner = createUser("selflinker");
        Project project = createProject(owner, "Selfish", "private");
        long pathId = createPath(owner, project, "Selfish path");
        long ideaId = createIdea(owner, pathId, "Alone");

        mockMvc.perform(post("/api/idea/" + ideaId + "/link/" + ideaId).header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unlinkRemovesEitherDirection() throws Exception {
        User owner = createUser("unlinker");
        Project project = createProject(owner, "Unlinking", "private");
        long pathId = createPath(owner, project, "Unlinking path");
        long ideaA = createIdea(owner, pathId, "A");
        long ideaB = createIdea(owner, pathId, "B");

        mockMvc.perform(post("/api/idea/" + ideaA + "/link/" + ideaB).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/idea/" + ideaB + "/link/" + ideaA).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(ideaLinkRepository.count()).isZero();
    }

    @Test
    void cannotLinkToAnotherUsersIdea() throws Exception {
        User owner = createUser("linkowner");
        User other = createUser("linkother");
        Project mine = createProject(owner, "MineL", "private");
        Project theirs = createProject(other, "TheirsL", "private");
        long myPath = createPath(owner, mine, "My path");
        long theirPath = createPath(other, theirs, "Their path");
        long myIdea = createIdea(owner, myPath, "My idea");
        long theirIdea = createIdea(other, theirPath, "Their idea");

        mockMvc.perform(post("/api/idea/" + myIdea + "/link/" + theirIdea).header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPathsForProjectReturnsAllOfThem() throws Exception {
        User owner = createUser("pathlister");
        Project project = createProject(owner, "Listable", "private");
        createPath(owner, project, "First");
        createPath(owner, project, "Second");

        mockMvc.perform(get("/api/project/" + project.getId() + "/path").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updatePathChangesTitleAndVisibility() throws Exception {
        User owner = createUser("pathupdater");
        Project project = createProject(owner, "Updatable", "private");
        long pathId = createPath(owner, project, "Original");

        mockMvc.perform(put("/api/path/" + pathId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed","visibility":"public"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"));

        assertThat(pathRepository.findById(pathId).orElseThrow().getVisibility()).isEqualTo("public");
    }

    @Test
    void listIdeasForPathReturnsAllOfThem() throws Exception {
        User owner = createUser("idealister");
        Project project = createProject(owner, "IdeaContainer", "private");
        long pathId = createPath(owner, project, "Path with ideas");
        createIdea(owner, pathId, "Idea one");
        createIdea(owner, pathId, "Idea two");

        mockMvc.perform(get("/api/path/" + pathId + "/idea").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updateContentCreatesContentWhenNoneExistsYet() throws Exception {
        User owner = createUser("contentcreator");
        Project project = createProject(owner, "ContentContainer", "private");
        long pathId = createPath(owner, project, "Path");
        long ideaId = createIdea(owner, pathId, "Fresh idea");

        mockMvc.perform(put("/api/idea/" + ideaId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Hello world"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/idea/" + ideaId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Hello world"));
    }
}
