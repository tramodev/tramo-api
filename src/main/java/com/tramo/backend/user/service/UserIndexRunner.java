package com.tramo.backend.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// JwtAuthFilter resolves the authenticated user on every request via
// findByUsernameIgnoreCase, which compiles to `WHERE lower(username) = lower(?)`.
// The unique constraint on the raw `username` column doesn't cover that expression,
// so every authenticated request was a sequential scan. A functional index on
// lower(username) fixes it; there's no Flyway/Liquibase here (ddl-auto=update),
// so this creates it idempotently at boot, same pattern as the other *Runner classes.
@Component
public class UserIndexRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UserIndexRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public UserIndexRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_users_lower_username ON users (lower(username))");
        log.info("Verified idx_users_lower_username exists");
    }
}
