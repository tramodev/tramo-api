package com.tramo.backend.comment;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommentTest extends AbstractIntegrationTest {

    @Test
    void postingAndListingCommentsWithReplies() throws Exception {
        User owner = createUser("commentowner");
        User fan = createUser("commentfan");
        Project project = createProject(owner, "Discuss me", "published", "A description", null);

        long topLevelId = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"Great project!"}""");

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Thanks!","parentId":%d}""".formatted(topLevelId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("Great project!"))
                .andExpect(jsonPath("$[0].authorUsername").value("commentfan"))
                .andExpect(jsonPath("$[1].content").value("Thanks!"))
                .andExpect(jsonPath("$[1].parentId").value(topLevelId));
    }

    @Test
    void commentingNotifiesOwnerButNotSelf() throws Exception {
        User owner = createUser("notifcommentowner");
        User fan = createUser("notifcommentfan");
        Project project = createProject(owner, "Notify me", "published", "A description", null);

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(fan))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Nice work"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("COMMENT"))
                .andExpect(jsonPath("$[0].latestActorUsername").value("notifcommentfan"));

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Commenting on my own project"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void deletingACommentWithRepliesSoftDeletesToPreserveThread() throws Exception {
        User owner = createUser("softdelowner");
        User fan = createUser("softdelfan");
        Project project = createProject(owner, "Thread me", "published", "A description", null);

        long parentId = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"Parent comment"}""");
        postForId(owner, "/api/project/" + pid(project) + "/comments", """
                {"content":"A reply","parentId":%d}""".formatted(parentId));

        mockMvc.perform(delete("/api/comment/" + parentId).header("Authorization", bearer(fan)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].deleted").value(true))
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].parentId").value(parentId));
    }

    @Test
    void onlyAuthorOwnerOrAdminCanDeleteAComment() throws Exception {
        User owner = createUser("permowner");
        User fan = createUser("permfan");
        User stranger = createUser("permstranger");
        User admin = createAdmin("permadmin");
        Project project = createProject(owner, "Locked down", "published", "A description", null);

        long commentId = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"My comment"}""");

        mockMvc.perform(delete("/api/comment/" + commentId).header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/comment/" + commentId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        long commentId2 = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"Another one"}""");
        mockMvc.perform(delete("/api/comment/" + commentId2).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void reportingACommentIsListedForAdminAndDismissable() throws Exception {
        User owner = createUser("reportcommentowner");
        User fan = createUser("reportcommentfan");
        User admin = createAdmin("reportcommentadmin");
        Project project = createProject(owner, "Reportable", "published", "A description", null);

        long commentId = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"Spam or something"}""");

        mockMvc.perform(post("/api/comment/" + commentId + "/report")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"This is spam"}"""))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='COMMENT')].commentContent").value(org.hamcrest.Matchers.contains("Spam or something")))
                .andReturn().getResponse().getContentAsString();
        long reportId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$[0].id")).longValue();

        mockMvc.perform(post("/api/admin/reports/" + reportId + "/dismiss?type=COMMENT")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void cannotCommentOnPrivateProject() throws Exception {
        User owner = createUser("privateowner");
        User fan = createUser("privatefan");
        Project project = createProject(owner, "Secret", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(fan))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Can I see this?"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingProjectRemovesItsComments() throws Exception {
        User owner = createUser("cleanupowner");
        User fan = createUser("cleanupfan");
        Project project = createProject(owner, "Ephemeral", "published", "A description", null);

        long parentId = postForId(fan, "/api/project/" + pid(project) + "/comments", """
                {"content":"Parent"}""");
        postForId(owner, "/api/project/" + pid(project) + "/comments", """
                {"content":"Reply","parentId":%d}""".formatted(parentId));

        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }
}
