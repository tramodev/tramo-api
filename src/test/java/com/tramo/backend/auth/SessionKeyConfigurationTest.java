package com.tramo.backend.auth;

import com.tramo.backend.auth.service.SessionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionKeyConfigurationTest {
    private static final String JWT_KEY = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQ=";
    private static final String REFRESH_KEY = "rRbqGbbP1Sfjmqi+lEZKzNlTztGZy1wcqzu9sVEgEAU=";

    private SessionService service(String key) {
        return new SessionService(null, null, null, null, null, null, null, key, JWT_KEY);
    }

    @Test
    void refreshKeyMustBeStrongAndIndependentOfJwtKey() {
        assertThatCode(() -> service(REFRESH_KEY)).doesNotThrowAnyException();
        for (String key : new String[]{"", "c2hvcnQ=", "not base64!", JWT_KEY}) {
            assertThatThrownBy(() -> service(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
