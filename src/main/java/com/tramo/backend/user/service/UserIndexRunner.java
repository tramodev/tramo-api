package com.tramo.backend.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// JwtAuthFilter resolves the authenticated user on every request via
// findByUsernameIgnoreCase, which compiles to `WHERE lower(username) = lower(?)`.
// The unique constraint on the raw `username` column doesn't cover that expression,
// so every authenticated request was a sequential scan. A functional index on
// lower(username) fixes it; there's no Flyway/Liquibase here (ddl-auto=update),
// so this creates it idempotently at boot, same pattern as the other *Runner classes.
//
// Also: `email` has no DB-level unique constraint at all - uniqueness is only
// enforced by an existsByEmail() check in AuthService before insert, which is a
// classic check-then-act race (two concurrent registrations with the same email
// both pass the check, both insert). Deliberately NOT added via @UniqueConstraint
// on the User entity: with ddl-auto=update, Hibernate would try to add it at boot
// via schema validation, which crashes startup if duplicate emails already exist in
// the DB. Doing it here instead means a pre-existing duplicate only logs a warning
// (and skips creating the constraint) rather than taking the whole app down.
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

        List<String> duplicateEmails = jdbcTemplate.queryForList(
                "SELECT email FROM users WHERE email IS NOT NULL GROUP BY email HAVING count(*) > 1",
                String.class);
        if (!duplicateEmails.isEmpty()) {
            log.error("Skipping unique constraint on users.email - duplicate emails found: {}. " +
                    "Manually dedupe these rows, then restart to have the constraint applied.", duplicateEmails);
            return;
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique ON users (email)");
        log.info("Verified idx_users_email_unique exists");
    }
}
