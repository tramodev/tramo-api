package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.common.ProjectIdSaltGuard;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectIdSaltGuardTest extends AbstractIntegrationTest {
    @Test
    void firstStartRecordsTheSaltAndLaterStartsMustMatch() throws Exception {
        var guard = new ProjectIdSaltGuard("stable-salt", jdbcTemplate);
        guard.run(null);
        String fingerprint = jdbcTemplate.queryForObject("SELECT fingerprint FROM project_id_salt_guard WHERE id = 1", String.class);
        guard.run(null);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_id_salt_guard", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> new ProjectIdSaltGuard("changed-salt", jdbcTemplate).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROJECT_ID_SALT changed");
        assertThat(jdbcTemplate.queryForObject("SELECT fingerprint FROM project_id_salt_guard WHERE id = 1", String.class)).isEqualTo(fingerprint);
    }

    @Test
    void blankSaltIsRejectedWithoutRecordingIt() {
        assertThatThrownBy(() -> new ProjectIdSaltGuard(" ", jdbcTemplate).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_id_salt_guard", Long.class)).isZero();
    }
}
