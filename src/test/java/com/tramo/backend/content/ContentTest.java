package com.tramo.backend.content;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.content.service.ContentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentTest extends AbstractIntegrationTest {
    @Autowired ContentService content;

    private String blogBody(String slug, boolean published) {
        return """
                {"slug":"%s","title":"Title","summary":"Summary","category":"Ideas","content":"Body","published":%s,"publishedDate":"2026-10-15"}
                """.formatted(slug, published);
    }

    @Test
    void onlyAdminsCanManageContentAndDraftsAreNotPublic() throws Exception {
        var admin = createAdmin("contentadmin");
        var user = createUser("contentreader");
        mockMvc.perform(get("/api/admin/content")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/content/blog").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(blogBody("private-post", false)))
                .andExpect(status().isForbidden());
        long id = postForId(admin, "/api/admin/content/blog", blogBody("private-post", false));
        mockMvc.perform(get("/api/public/content/blog")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/public/content/blog/private-post")).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/admin/content/blog/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(blogBody("public-post", true))).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/content/blog")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("public-post")).andExpect(jsonPath("$[0].content").doesNotExist());
        mockMvc.perform(get("/api/public/content/blog/public-post")).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Body"));
        mockMvc.perform(post("/api/admin/content/blog").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(blogBody("public-post", true))).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/content/blog/" + id).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/content/blog/public-post")).andExpect(status().isNotFound());
    }

    @Test
    void managesChangelogAndRoadmapVisibilityOrderAndDeletion() throws Exception {
        var admin = createAdmin("contenteditor");
        long change = postForId(admin, "/api/admin/content/changelog", """
                {"title":"Update","content":"Changes","published":false,"publishedDate":"2026-10-15"}
                """);
        mockMvc.perform(get("/api/public/content/changelog")).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(put("/api/admin/content/changelog/" + change).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Update","content":"Changes","published":true,"publishedDate":"2026-10-15"}
                        """)).andExpect(status().isOk());
        long item = postForId(admin, "/api/admin/content/roadmap", """
                {"title":"Later","description":"More","status":"PLANNED","position":2,"published":true}
                """);
        postForId(admin, "/api/admin/content/roadmap", """
                {"title":"First","description":"More","status":"IN_PROGRESS","position":0,"published":true}
                """);
        mockMvc.perform(get("/api/public/content/roadmap")).andExpect(jsonPath("$[0].title").value("First"));
        mockMvc.perform(put("/api/admin/content/roadmap/" + item).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Hidden","description":"More","status":"COMPLETED","position":2,"published":false}
                        """)).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/content/roadmap")).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/admin/content").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roadmap.length()").value(2));
        mockMvc.perform(delete("/api/admin/content/changelog/" + change).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/content/roadmap/" + item).header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidContent() throws Exception {
        var admin = createAdmin("contentvalidation");
        mockMvc.perform(post("/api/admin/content/blog").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(blogBody("bad/slug", true))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/content/blog").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(blogBody("empty-post", true).replace("\"Body\"", "\"\""))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/content/roadmap").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"AI","description":"More","status":"UNKNOWN","position":-1,"published":true}
                        """)).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "USER")
    void serviceEnforcesAdminAuthorization() {
        assertThatThrownBy(() -> content.listAdminContent()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.deleteBlog(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.saveRoadmap(null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
