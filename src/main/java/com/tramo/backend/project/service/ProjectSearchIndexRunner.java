package com.tramo.backend.project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// The public search/explore queries (ProjectRepository.findPublishedRecent and friends) use
// LOWER(col) LIKE LOWER('%query%') - a leading wildcard, which a normal btree index can never
// use, so every search is a sequential scan over the whole table. pg_trgm's GIN operator class
// makes Postgres' planner use an index for exactly this pattern, with no query rewrite needed.
// There's no Flyway/Liquibase here (ddl-auto=update), so this installs the extension and
// indexes idempotently at boot, same pattern as the other *Runner classes. Best-effort: if the
// DB user lacks privilege to install the extension, this logs and moves on rather than crashing
// startup - searches just stay slow until an operator runs it manually.
@Component
public class ProjectSearchIndexRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProjectSearchIndexRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public ProjectSearchIndexRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            // Indexed on lower(col), not the raw column - Hibernate generates
            // WHERE lower(col) LIKE lower(?), and Postgres only matches an index to a query
            // when the indexed expression is identical to the one in the WHERE clause.
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_project_title_trgm ON project USING gin (lower(title) gin_trgm_ops)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_project_description_trgm ON project USING gin (lower(description) gin_trgm_ops)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_tag_name_trgm ON tag USING gin (lower(name) gin_trgm_ops)");
            log.info("Verified pg_trgm search indexes exist on lower(project.title), lower(project.description), lower(tag.name)");
        } catch (Exception e) {
            log.warn("Could not install pg_trgm search indexes - search queries will fall back to " +
                    "sequential scans until this is run manually with sufficient DB privileges", e);
        }
    }
}
