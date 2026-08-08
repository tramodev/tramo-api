package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrivacyEnforcementTest extends AbstractIntegrationTest {

    private void setPreference(User user, String json) throws Exception {
        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void follow(User follower, String target) throws Exception {
        mockMvc.perform(post("/api/users/" + target + "/follow").header("Authorization", bearer(follower)))
                .andExpect(status().isOk());
    }

    @Test
    void privateProfileIsHiddenFromAnonymousAndStrangers() throws Exception {
        var owner = createUser("privowner");
        var stranger = createUser("privstranger");
        setPreference(owner, """
                {"profileVisibility":"private"}""");

        mockMvc.perform(get("/api/public/users/privowner"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/users/privowner").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateProfileStaysHiddenFromAMereFollowerButOpensWhenTheOwnerFollowsBack() throws Exception {
        var owner = createUser("privfollowed");
        var viewer = createUser("privviewer");
        setPreference(owner, """
                {"profileVisibility":"private"}""");

        follow(viewer, "privfollowed");
        mockMvc.perform(get("/api/public/users/privfollowed").header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound());

        follow(owner, "privviewer");
        mockMvc.perform(get("/api/public/users/privfollowed").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("privfollowed"));
    }

    @Test
    void privateProfileIsVisibleToSelfAndAdmins() throws Exception {
        var owner = createUser("privself");
        var admin = createAdmin("privadmin");
        setPreference(owner, """
                {"profileVisibility":"private"}""");

        mockMvc.perform(get("/api/public/users/privself").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.self").value(true));
        mockMvc.perform(get("/api/public/users/privself").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void privateProfileHidesFollowersFollowingPublishedAndUpvoted() throws Exception {
        var owner = createUser("privlists");
        setPreference(owner, """
                {"profileVisibility":"private"}""");

        for (String path : new String[]{"followers", "following", "published", "upvoted"}) {
            mockMvc.perform(get("/api/public/users/privlists/" + path))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void privateUsersPublishedProjectStaysPubliclyReadable() throws Exception {
        var owner = createUser("privpublisher");
        Project project = createProject(owner, "Still public", "published");
        setPreference(owner, """
                {"profileVisibility":"private"}""");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Still public"));
    }

    @Test
    void commentsPolicyNooneRejectsEveryoneButTheOwner() throws Exception {
        var owner = createUser("cmtnoone");
        var other = createUser("cmtother");
        Project project = createProject(owner, "No comments", "published");
        setPreference(owner, """
                {"commentsPolicy":"noone"}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"hello"}"""))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"my own project"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void commentsPolicyFollowingOnlyAllowsPeopleTheOwnerFollows() throws Exception {
        var owner = createUser("cmtfollowowner");
        var other = createUser("cmtfollowother");
        Project project = createProject(owner, "Followers only", "published");
        setPreference(owner, """
                {"commentsPolicy":"following"}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"let me in"}"""))
                .andExpect(status().isForbidden());

        follow(owner, "cmtfollowother");

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"thanks"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void publicProjectCarriesCanCommentForTheRequester() throws Exception {
        var owner = createUser("cancmtowner");
        var other = createUser("cancmtother");
        Project project = createProject(owner, "Flagged", "published");
        setPreference(owner, """
                {"commentsPolicy":"noone"}""");

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canComment").value(false));
    }

    @Test
    void allowForksFalseRejectsForksAndIsReportedOnThePublicProject() throws Exception {
        var owner = createUser("noforkowner");
        var other = createUser("noforkother");
        Project project = createProject(owner, "Do not fork", "published");
        setPreference(owner, """
                {"allowForks":false}""");

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canFork").value(false));

        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void forksStayAllowedByDefault() throws Exception {
        var owner = createUser("forkowner");
        var other = createUser("forkother");
        Project project = createProject(owner, "Fork me", "published");

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canFork").value(true));

        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(other)))
                .andExpect(status().isOk());
    }

    @Test
    void showUpvotesGatesThePublicUpvotedPage() throws Exception {
        var owner = createUser("upvowner");
        var other = createUser("upvother");
        Project project = createProject(other, "Voted", "published");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/users/upvowner/upvoted").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Voted"));

        setPreference(owner, """
                {"showUpvotes":false}""");

        mockMvc.perform(get("/api/public/users/upvowner").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showUpvotes").value(false));
        mockMvc.perform(get("/api/public/users/upvowner/upvoted").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/users/upvowner/upvoted").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void publicUpvotedPageOmitsProjectsThatAreNoLongerPublished() throws Exception {
        var owner = createUser("upvhiddenowner");
        var other = createUser("upvhiddenother");
        Project project = createProject(other, "Unpublished later", "published");
        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        project.setVisibility(com.tramo.backend.project.entity.ProjectVisibility.PRIVATE);
        projectRepository.save(project);

        mockMvc.perform(get("/api/public/users/upvhiddenowner/upvoted").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
