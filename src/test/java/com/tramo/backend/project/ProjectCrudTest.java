package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.user.entity.User;
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

class ProjectCrudTest extends AbstractIntegrationTest {

    @Autowired
    ProjectVoteRepository projectVoteRepository;

    @Autowired
    ProjectBookmarkRepository projectBookmarkRepository;

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Anon project"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRequiresTitle() throws Exception {
        User owner = createUser("maker");
        mockMvc.perform(post("/api/project")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsInvalidVisibility() throws Exception {
        User owner = createUser("maker2");
        mockMvc.perform(post("/api/project")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Valid","visibility":"everyone"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.visibility").exists());
    }

    @Test
    void createNormalizesTags() throws Exception {
        User owner = createUser("tagger");
        String id = postForProjectId(owner, "/api/project", """
                {"title":"Tagged","tags":"Java, SQL , java, "}""");

        assertThat(projectRepository.findById(projectIdCodec.decode(id)).orElseThrow().getTags()).isEqualTo("java,sql");
    }

    @Test
    void getAllReturnsOnlyOwnProjects() throws Exception {
        User owner = createUser("mine");
        User other = createUser("theirs");
        createProject(owner, "Mine A", "private");
        createProject(owner, "Mine B", "published");
        createProject(other, "Not mine", "published");

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getByIdReturnsOwnProject() throws Exception {
        User owner = createUser("reader");
        Project project = createProject(owner, "Readable", "private", "desc", "tag1");

        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Readable"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    void getByIdRejectsOthersProject() throws Exception {
        User owner = createUser("owner1");
        User intruder = createUser("intruder1");
        Project project = createProject(owner, "Secret", "private");

        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getByIdUnknownProjectIs404() throws Exception {
        User user = createUser("seeker");
        mockMvc.perform(get("/api/project/999999").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateChangesOnlyProvidedFields() throws Exception {
        User owner = createUser("editor");
        Project project = createProject(owner, "Original", "private", "old desc", null);

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"new desc"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.description").value("new desc"));
    }

    @Test
    void updateIgnoresBlankTitle() throws Exception {
        User owner = createUser("editor2");
        Project project = createProject(owner, "Keep me", "private");

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Keep me"));
    }

    @Test
    void updateRejectsOthersProject() throws Exception {
        User owner = createUser("owner2");
        User intruder = createUser("intruder2");
        Project project = createProject(owner, "Untouchable", "private");

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Hacked"}"""))
                .andExpect(status().isForbidden());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTitle())
                .isEqualTo("Untouchable");
    }

    @Test
    void publishMakesProjectVisibleInExplore() throws Exception {
        User owner = createUser("publisher");
        Project project = createProject(owner, "Going public", "private", "A description", null);

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed[0].title").value("Going public"));
    }

    @Test
    void followingSortOnlyShowsFollowedAuthorsProjects() throws Exception {
        User me = createUser("followsortme");
        User followed = createUser("followsortfollowed");
        User stranger = createUser("followsortstranger");

        mockMvc.perform(post("/api/users/followsortfollowed/follow").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        createProject(followed, "From someone I follow", "published", "A description", null);
        createProject(stranger, "From a stranger", "published", "A description", null);

        mockMvc.perform(get("/api/public/explore?sort=following").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1))
                .andExpect(jsonPath("$.feed[0].title").value("From someone I follow"));

        mockMvc.perform(get("/api/public/explore?sort=following").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0));

        mockMvc.perform(get("/api/public/explore?sort=following"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0));
    }

    @Test
    void deleteRemovesProjectWithPathsIdeasVotesAndBookmarks() throws Exception {
        User owner = createUser("demolisher");
        User fan = createUser("fan");
        Project project = createProject(owner, "Doomed", "published");
        long pathId = postForId(owner, "/api/project/" + pid(project) + "/path", """
                {"title":"Doomed path"}""");
        long ideaId = postForId(owner, "/api/path/" + pathId + "/idea", """
                {"title":"Doomed idea"}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(projectRepository.findById(project.getId())).isEmpty();
        assertThat(projectVoteRepository.count()).isZero();
        assertThat(projectBookmarkRepository.count()).isZero();
        mockMvc.perform(get("/api/idea/" + ideaId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteKeepsForksButClearsForkReference() throws Exception {
        User owner = createUser("origin");
        User forker = createUser("forker0");
        Project source = createProject(owner, "Forkable", "published");

        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");

        mockMvc.perform(delete("/api/project/" + pid(source)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        Project fork = projectRepository.findById(projectIdCodec.decode(forkId)).orElseThrow();
        assertThat(fork.getForkedFrom()).isNull();
    }

    @Test
    void deleteRejectsOthersProject() throws Exception {
        User owner = createUser("owner3");
        User intruder = createUser("intruder3");
        Project project = createProject(owner, "Protected", "private");

        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());

        assertThat(projectRepository.findById(project.getId())).isPresent();
    }

    @Test
    void publicFeedReturnsPublishedProjects() throws Exception {
        User author = createUser("feedauthor");
        createProject(author, "Feed item", "published", "d", "tag");

        mockMvc.perform(get("/api/public/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Feed item"));
    }

    @Test
    void publicTagsReturnsHotTopics() throws Exception {
        User author = createUser("tagauthor");
        createProject(author, "Tagged", "published", "d", "popular");

        mockMvc.perform(get("/api/public/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tag").value("popular"));
    }

    @Test
    void followTogglesOnAndOff() throws Exception {
        User follower = createUser("followerx");
        createUser("followedx");

        mockMvc.perform(post("/api/users/followedx/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(post("/api/users/followedx/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void followersAndFollowingListsArePaginatedAndReflectRequesterState() throws Exception {
        User alice = createUser("flalice");
        User bob = createUser("flbob");
        User carol = createUser("flcarol");

        mockMvc.perform(post("/api/users/flalice/follow").header("Authorization", bearer(bob)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/flalice/follow").header("Authorization", bearer(carol)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/flbob/follow").header("Authorization", bearer(alice)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/users/flalice/followers?page=0&size=1").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));

        mockMvc.perform(get("/api/public/users/flalice/followers?page=1&size=1").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));

        mockMvc.perform(get("/api/public/users/flalice/following").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("flbob"));

        mockMvc.perform(get("/api/public/users/flbob/following").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("flalice"));

        mockMvc.perform(get("/api/public/users/flalice/followers").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username == 'flcarol')].followingByRequester").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void publicProfilePublishedListIsPaginated() throws Exception {
        User author = createUser("pubpubauthor");
        for (int i = 0; i < 3; i++) {
            createProject(author, "Public Pub " + i, "published");
        }

        mockMvc.perform(get("/api/public/users/pubpubauthor/published?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));

        mockMvc.perform(get("/api/public/users/pubpubauthor/published?page=1&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void publishingWithoutDescriptionIsRejected() throws Exception {
        User owner = createUser("nodescowner");
        Project project = createProject(owner, "No description yet", "private");

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"A real description now"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("published"));
    }

    @Test
    void thumbnailOnlyUpdateDoesNotBumpModifiedDate() throws Exception {
        User owner = createUser("thumbnailupdater");
        Project project = createProject(owner, "ThumbnailProject", "private");
        java.util.Date staleDate = new java.util.Date(System.currentTimeMillis() - 60_000);
        project.setModifiedDate(staleDate);
        projectRepository.save(project);

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thumbnail":"data:image/png;base64,abc"}"""))
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getModifiedDate())
                .isEqualTo(staleDate);

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed"}"""))
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getModifiedDate())
                .isAfter(staleDate);
    }

    @Test
    void updateProfileChangesBioAndImage() throws Exception {
        User user = createUser("profileupdater");

        mockMvc.perform(put("/api/profile/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio":"New bio","imageUrl":"https://example.com/a.png"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("New bio"));
    }
}
