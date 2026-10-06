package com.tramo.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionLoggingConfigTest {
    @Test
    void prodLoadsSafeDefaultsWithoutExternalServices() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod", "spring.config.import=",
                        "DATABASE_URL=jdbc:postgresql://unused/test", "FRONTEND_URL=https://unused.test",
                        "PATREON_REDIRECT_URI=https://unused.test/callback")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConfigurableEnvironment env = context.getEnvironment();
                    assertThat(env.getActiveProfiles()).contains("prod");
                    for (String key : new String[]{"spring.jpa.show-sql", "spring.jpa.properties.hibernate.format_sql",
                            "spring.mvc.log-request-details", "spring.mail.properties.mail.debug",
                            "spring.mail.properties.mail.debug.auth"}) {
                        assertThat(env.getProperty(key)).as(key).isEqualTo("false");
                    }
                    WebProperties web = Binder.get(env).bind("spring.web", WebProperties.class).get();
                    assertThat(web.getError().isIncludeException()).isFalse();
                    assertThat(web.getError().getIncludeMessage().name()).isEqualTo("NEVER");
                    assertThat(web.getError().getIncludeStacktrace().name()).isEqualTo("NEVER");
                    assertThat(web.getError().getIncludeBindingErrors().name()).isEqualTo("NEVER");
                    assertThat(web.getError().getIncludePath().name()).isEqualTo("NEVER");
                    for (String name : new String[]{"org.hibernate.orm.connections.pooling", "org.hibernate.SQL", "org.hibernate.orm.jdbc.bind",
                            "org.hibernate.orm.jdbc.extract", "org.hibernate.orm.jdbc.error", "org.hibernate.orm.jdbc.warn",
                            "org.hibernate.engine.jdbc.spi.SqlExceptionHelper", "software.amazon.awssdk.request",
                            "org.apache.http.wire", "org.apache.http.headers", "org.apache.hc.client5.http.wire"}) {
                        assertThat(env.getProperty("logging.level." + name)).as(name).isEqualTo("OFF");
                    }
                    assertThat(env.getProperty("logging.level.org.flywaydb.core.FlywayExecutor")).isEqualTo("WARN");
                    assertThat(env.getProperty("logging.level.root")).isEqualTo("INFO");
                    assertThat(env.getProperty("logging.level.org.springframework.security")).isEqualTo("WARN");
                });
    }
}
