package com.tramo.backend.subscription;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.security.crypto.EncryptedStringConverter;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatreonCredentialIsolationTest extends AbstractIntegrationTest {
    @Test
    void aTokenFromAnotherKeyDoesNotBreakUserReadsOrGetOverwritten() throws Exception {
        var owner = createUser("rotatedkey");
        var admin = createAdmin("rotationadmin");
        var previousCipher = new EncryptedStringConverter(Base64.getEncoder().encodeToString(new byte[32]));
        String stored = previousCipher.convertToDatabaseColumn("previous-access-token");
        owner.setPatreonUserId("linked-patreon");
        owner.setEncryptedPatreonAccessToken(stored);
        owner.setEncryptedPatreonRefreshToken(previousCipher.convertToDatabaseColumn("previous-refresh-token"));
        userRepository.save(owner);
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"rotatedkey\",\"password\":\"Passw0rd123!\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/users/rotatedkey")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"bio\":\"Updated profile\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT patreon_access_token FROM users WHERE id = ?", String.class, owner.getId())).isEqualTo(stored);
        mockMvc.perform(post("/api/subscription/patreon/sync").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PATREON_RECONNECT_REQUIRED"));
        verify(patreonClient, never()).fetchIdentity(anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT patreon_access_token FROM users WHERE id = ?", String.class, owner.getId())).isEqualTo(stored);
    }
}
