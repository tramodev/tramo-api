package com.tramo.backend.security.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@Profile("prod")
public class ProductionConfigurationGuard {
    public ProductionConfigurationGuard(Environment environment) {
        for (String property : new String[]{
                "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "app.google.client-id", "app.captcha.secret-key", "app.patreon.client-id",
                "app.patreon.client-secret", "app.patreon.webhook-secret", "app.jwt.secret",
                "app.auth.refresh-derivation-key", "app.security.bff-signing-key",
                "app.security.token-encryption-key", "app.project-id.salt", "app.r2.account-id",
                "app.r2.access-key", "app.r2.secret-key", "app.r2.bucket", "app.r2.private-bucket"
        }) {
            require(environment, property);
        }
        if (!require(environment, "spring.datasource.url").startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("spring.datasource.url must be a PostgreSQL JDBC URL");
        }
        for (String property : new String[]{"app.frontend-url", "app.patreon.redirect-uri",
                "app.r2.public-base-url", "app.r2.endpoint", "app.captcha.base-url"}) {
            URI uri = URI.create(require(environment, property));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException(property + " must be an HTTPS URL");
            }
        }
        if (environment.getProperty("app.mail.enabled", Boolean.class, true)) {
            require(environment, "spring.mail.password");
        }
    }

    private String require(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required in production");
        }
        return value;
    }
}
