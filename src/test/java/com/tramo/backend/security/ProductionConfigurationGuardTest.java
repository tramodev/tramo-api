package com.tramo.backend.security;

import com.tramo.backend.security.config.ProductionConfigurationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {
    private MockEnvironment configured() {
        var environment = new MockEnvironment();
        for (String property : new String[]{
                "spring.datasource.username", "spring.datasource.password", "app.google.client-id",
                "app.captcha.secret-key", "app.patreon.client-id", "app.patreon.client-secret",
                "app.patreon.webhook-secret", "app.jwt.secret", "app.auth.refresh-derivation-key",
                "app.security.bff-signing-key", "app.security.token-encryption-key", "app.project-id.salt",
                "app.r2.account-id", "app.r2.access-key", "app.r2.secret-key", "app.r2.bucket",
                "app.r2.private-bucket", "spring.mail.password"
        }) environment.setProperty(property, "configured");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://db/tramo");
        for (String property : new String[]{"app.frontend-url", "app.patreon.redirect-uri",
                "app.r2.public-base-url", "app.r2.endpoint", "app.captcha.base-url"}) {
            environment.setProperty(property, "https://tramo.example");
        }
        return environment;
    }

    @Test
    void requiresSecretsAndHttpsInProduction() {
        assertThatCode(() -> new ProductionConfigurationGuard(configured())).doesNotThrowAnyException();
        for (String property : new String[]{"app.security.bff-signing-key", "app.captcha.secret-key",
                "app.auth.refresh-derivation-key", "spring.mail.password"}) {
            assertThatThrownBy(() -> new ProductionConfigurationGuard(configured().withProperty(property, " ")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(property);
        }
        assertThatThrownBy(() -> new ProductionConfigurationGuard(configured()
                .withProperty("app.frontend-url", "http://localhost:3000")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new ProductionConfigurationGuard(configured()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:test")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL");
    }

    @Test
    void disabledMailDoesNotRequireACredential() {
        assertThatCode(() -> new ProductionConfigurationGuard(configured()
                .withProperty("app.mail.enabled", "false").withProperty("spring.mail.password", "")))
                .doesNotThrowAnyException();
    }
}
