package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ExploreService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExploreFilteringTest extends AbstractIntegrationTest {

    @Autowired
    private ExploreService exploreService;

    private Project published(User owner, String title, String tags) {
        return createProject(owner, title, "published", "A description", tags);
    }

    private void block(User blocker, String target) throws Exception {
        mockMvc.perform(post("/api/users/" + target + "/block").header("Authorization", bearer(blocker)))
                .andExpect(status().isOk());
    }

    @Test
    void featuredProjectIsIncludedOnTheFirstPage() throws Exception {
        User owner = createUser("expowner1");
        published(owner, "Featurable", "java");
        exploreService.refreshFeaturedProject();
        exploreService.refreshExploreCache();

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured.title").value("Featurable"));
    }

    @Test
    void featuredProjectOfABlockedOwnerIsHidden() throws Exception {
        User owner = createUser("expowner2");
        User viewer = createUser("expviewer2");
        published(owner, "Featurable", "java");
        exploreService.refreshFeaturedProject();
        block(viewer, "expowner2");

        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(nullValue()));
    }

    @Test
    void featuredIsOmittedFromTheFollowingSort() throws Exception {
        User owner = createUser("expowner3");
        User viewer = createUser("expviewer3");
        published(owner, "Featurable", "java");
        exploreService.refreshFeaturedProject();

        mockMvc.perform(get("/api/public/explore?sort=following").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(nullValue()));
    }

    @Test
    void featuredIsOmittedBeyondTheFirstPage() throws Exception {
        User owner = createUser("expowner4");
        published(owner, "Featurable", "java");
        exploreService.refreshFeaturedProject();

        mockMvc.perform(get("/api/public/explore?page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(nullValue()));
    }

    @Test
    void blockedAuthorsAndTrendingProjectsAreFilteredFromTheCachedLists() throws Exception {
        User owner = createUser("expowner5");
        User viewer = createUser("expviewer5");
        published(owner, "Trendy", "java");
        exploreService.refreshExploreCache();

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAuthors[*].username", org.hamcrest.Matchers.hasItem("expowner5")));

        block(viewer, "expowner5");

        mockMvc.perform(get("/api/public/explore").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAuthors[*].username", everyItem(not("expowner5"))))
                .andExpect(jsonPath("$.trendingProjects[*].ownerUsername", everyItem(not("expowner5"))));
    }

    @Test
    void followingSortShowsOnlyProjectsOfFollowedAuthors() throws Exception {
        User followed = createUser("expfollowed6");
        User stranger = createUser("expstranger6");
        User viewer = createUser("expviewer6");
        published(followed, "From followed", null);
        published(stranger, "From stranger", null);
        mockMvc.perform(post("/api/users/expfollowed6/follow").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/explore?sort=following").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1))
                .andExpect(jsonPath("$.feed[0].title").value("From followed"));
    }

    @Test
    void followingSortIsEmptyWhenFollowingNobody() throws Exception {
        User owner = createUser("expowner7");
        User viewer = createUser("expviewer7");
        published(owner, "Somewhere", null);

        mockMvc.perform(get("/api/public/explore?sort=following").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void hotSortReturnsPublishedProjects() throws Exception {
        User owner = createUser("expowner8");
        published(owner, "Hot one", "java");

        mockMvc.perform(get("/api/public/explore?sort=hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed[0].title").value("Hot one"));
    }

    @Test
    void refreshingFeaturedTwiceKeepsTheSameProject() throws Exception {
        User owner = createUser("expowner9");
        published(owner, "Stable", null);

        exploreService.refreshFeaturedProject();
        exploreService.refreshFeaturedProject();

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured.title").value("Stable"));
    }

    @Test
    void refreshingFeaturedWithoutProjectsDoesNothing() {
        exploreService.refreshFeaturedProject();
    }

    @Test
    void forkSourceOfABlockedOwnerIsHiddenFromTheFeed() throws Exception {
        User sourceOwner = createUser("expsource10");
        User forker = createUser("expforker10");
        User viewer = createUser("expviewer10");
        Project source = published(sourceOwner, "Original", null);
        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        mockMvc.perform(post("/api/project/" + forkId + "/publish").header("Authorization", bearer(forker)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/users/expforker10/published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].forkedFromOwnerUsername").value("expsource10"));

        block(viewer, "expsource10");

        mockMvc.perform(get("/api/public/users/expforker10/published").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].forkedFromOwnerUsername").value(nullValue()))
                .andExpect(jsonPath("$.content[0].forkedFromProjectId").value(nullValue()));
    }
}
