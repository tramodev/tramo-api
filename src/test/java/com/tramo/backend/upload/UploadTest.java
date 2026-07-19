package com.tramo.backend.upload;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadTest extends AbstractIntegrationTest {

    private static final String FAKE_HASH = "0123456789abcdef".repeat(4);

    @Test
    void presignRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/uploads/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"avatar","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void presignRejectsDisallowedContentType() throws Exception {
        User user = createUser("uploader1");

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"application/pdf","kind":"avatar","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presignRejectsDisallowedKind() throws Exception {
        User user = createUser("uploader2");

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"banana","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presignRejectsMalformedContentHash() throws Exception {
        User user = createUser("uploader4");

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"avatar","contentHash":"not-a-hash","contentBytes":1000}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presignReturnsWellFormedUrlsForAllowedContentType() throws Exception {
        User user = createUser("uploader3");

        String response = mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"avatar","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andExpect(jsonPath("$.publicUrl").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("/avatar/" + user.getId() + "/" + FAKE_HASH);
        assertThat(response).contains(".jpg");
    }

    @Test
    void presignIsDeterministicForSameContentHash() throws Exception {
        User user = createUser("uploader5");

        String first = mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(first).contains("\"publicUrl\"");
        String firstPublicUrl = first.split("\"publicUrl\":\"")[1].split("\"")[0];
        String secondPublicUrl = second.split("\"publicUrl\":\"")[1].split("\"")[0];
        assertThat(firstPublicUrl).isEqualTo(secondPublicUrl);
    }
}
