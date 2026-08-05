package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SitemapTest extends AbstractIntegrationTest {

    @Test
    void sitemapProjectsOnlyListsPublishedProjectsWithDecodableIds() throws Exception {
        User owner = createUser("sitemapprojectowner");
        var published = createProject(owner, "Published one", "published");
        createProject(owner, "Private one", "private");
        createProject(owner, "Unlisted one", "unlisted");

        mockMvc.perform(get("/api/public/sitemap/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pid(published)));
    }

    @Test
    void sitemapUsersExcludesPrivateAndBannedProfiles() throws Exception {
        createUser("sitemapvisible");
        createUser("sitemapbanned", "sitemapbanned@example.com", true, true, Role.USER);
        User privateUser = createUser("sitemapprivate", "sitemapprivate@example.com", true, false, Role.USER);
        privateUser.setVisibility(false);
        userRepository.save(privateUser);

        String usernames = mockMvc.perform(get("/api/public/sitemap/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(usernames).contains("sitemapvisible");
        assertThat(usernames).doesNotContain("sitemapbanned");
        assertThat(usernames).doesNotContain("sitemapprivate");
    }
}
