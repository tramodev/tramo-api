package com.tramo.backend.auth;

import com.tramo.backend.auth.service.OpaqueToken;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenTest {
    @Test
    void generatesRandomUrlSafe256BitTokens() {
        String token = OpaqueToken.generate();
        assertThat(token).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(OpaqueToken.generate());
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void hashesTokensWithSha256() {
        assertThat(OpaqueToken.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(OpaqueToken.hash("ABC")).isNotEqualTo(OpaqueToken.hash("abc"));
    }

    @Test
    void replacementIsStableAcrossInstancesAndBoundToTheTokenAndSecret() {
        byte[] key = Base64.getDecoder().decode("dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQ=");
        String token = OpaqueToken.generate();
        String replacement = OpaqueToken.replacement(token, key);
        assertThat(replacement).matches("[A-Za-z0-9_-]{43}")
                .isEqualTo(OpaqueToken.replacement(token, key.clone()))
                .isNotEqualTo(token)
                .isNotEqualTo(OpaqueToken.replacement(OpaqueToken.generate(), key));
        key[0] ^= 1;
        assertThat(OpaqueToken.replacement(token, key)).isNotEqualTo(replacement);
    }
}
