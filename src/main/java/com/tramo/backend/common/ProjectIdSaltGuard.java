package com.tramo.backend.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProjectIdSaltGuard implements ApplicationRunner {
    private final String salt;
    private final JdbcTemplate jdbc;

    public ProjectIdSaltGuard(@Value("${app.project-id.salt}") String salt, JdbcTemplate jdbc) {
        this.salt = salt;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws NoSuchAlgorithmException {
        if (salt.isBlank()) throw new IllegalStateException("PROJECT_ID_SALT must not be blank");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(salt.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO project_id_salt_guard (id, fingerprint) VALUES (1, ?) ON CONFLICT (id) DO NOTHING", fingerprint);
        String expected = jdbc.queryForObject("SELECT fingerprint FROM project_id_salt_guard WHERE id = 1", String.class);
        if (!fingerprint.equals(expected)) throw new IllegalStateException("PROJECT_ID_SALT changed. Restore the original salt to preserve public project URLs.");
    }
}
