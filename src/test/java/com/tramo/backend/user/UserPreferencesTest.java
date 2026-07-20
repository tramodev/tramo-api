package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserPreferencesTest extends AbstractIntegrationTest {

    @Test
    void defaultsWhenNothingSet() throws Exception {
        var user = createUser("prefsdefault");

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("public"))
                .andExpect(jsonPath("$.emailDigestFrequency").value("weekly"))
                .andExpect(jsonPath("$.showUpvotes").value(true))
                .andExpect(jsonPath("$.allowForks").value(true))
                .andExpect(jsonPath("$.commentsPolicy").value("everyone"));
    }

    @Test
    void partialUpdateDoesNotClobberOtherFields() throws Exception {
        var user = createUser("prefspartial");

        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emailDigestFrequency":"off"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailDigestFrequency").value("off"))
                .andExpect(jsonPath("$.profileVisibility").value("public"));

        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVisibility":"private","showUpvotes":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("private"))
                .andExpect(jsonPath("$.showUpvotes").value(false))
                .andExpect(jsonPath("$.allowForks").value(true))
                .andExpect(jsonPath("$.emailDigestFrequency").value("off"));

        mockMvc.perform(get("/user/preferences").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("private"))
                .andExpect(jsonPath("$.showUpvotes").value(false))
                .andExpect(jsonPath("$.emailDigestFrequency").value("off"));
    }

    @Test
    void invalidEnumValueRejected() throws Exception {
        var user = createUser("prefsinvalid");

        mockMvc.perform(put("/user/preferences")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commentsPolicy":"nonsense"}"""))
                .andExpect(status().isBadRequest());
    }
}
