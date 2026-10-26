package com.tramo.backend.project.service;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.dto.BadgeDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BadgeConcurrencyTest extends AbstractIntegrationTest {
    @Autowired BadgeService badges;
    @Autowired TransactionTemplate transactions;

    @Test
    void concurrentAwardsInsertOneBadgeAndOneNotification() throws Exception {
        var user = createUser("concurrentbadge");
        var barrier = new CyclicBarrier(4);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                transactions.executeWithoutResult(status -> badges.awardNewlyEarnedBadges(user,
                        List.of(new BadgeDTO("forked_once", "Forked Once", "Get forked", true, 1, 1))));
                return true;
            })).toList();
            for (var call : calls) assertThat(call.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_badge WHERE user_id = ?", Long.class, user.getId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_id = ?", Long.class, user.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void forkProgressBelongsToOriginalAuthor() throws Exception {
        var author = createUser("forkauthor");
        var forker = createUser("forkactor");
        var source = createProject(author, "Original", "published", "d", null);
        for (int i = 0; i < 25; i++) {
            var fork = createProject(forker, "Fork " + i, "private");
            fork.setForkedFrom(source);
            projectRepository.save(fork);
        }
        assertThat(badges.getProfileStats(author).getForksCount()).isEqualTo(25);
        assertThat(badges.getProfileStats(forker).getForksCount()).isZero();
        var remix = badges.buildBadges(badges.getProfileStats(author), author).stream()
                .filter(badge -> badge.getCode().equals("remix_king")).findFirst().orElseThrow();
        assertThat(remix.getProgress()).isEqualTo(1);
        assertThat(remix.isEarned()).isFalse();
        transactions.executeWithoutResult(status -> badges.checkAndAwardBadges(author));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_badge WHERE user_id = ? AND badge_code = 'remix_king'",
                Long.class, author.getId())).isZero();
        for (String path : List.of("/api/profile/stats", "/api/public/users/" + author.getUsername())) {
            mockMvc.perform(get(path).header("Authorization", bearer(author)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stats.forksCount").value(25))
                    .andExpect(jsonPath("$.badges[?(@.code=='remix_king')].progress").value(1))
                    .andExpect(jsonPath("$.badges[?(@.code=='remix_king')].earned").value(false));
        }
    }

    @Test
    void remixKingRequiresTwentyFiveDistinctForkersAcrossProjects() {
        var author = createUser("remixauthor");
        var first = createProject(author, "First", "unlisted");
        var second = createProject(author, "Second", "unlisted");
        for (int i = 0; i < 25; i++) {
            var forker = createUser("remixforker" + i);
            for (var source : List.of(first, second)) {
                var fork = createProject(forker, "Fork", "private");
                fork.setForkedFrom(source);
                projectRepository.save(fork);
            }
            var remix = badges.buildBadges(badges.getProfileStats(author), author).stream()
                    .filter(badge -> badge.getCode().equals("remix_king")).findFirst().orElseThrow();
            assertThat(remix.getProgress()).isEqualTo(i + 1);
            assertThat(remix.isEarned()).isEqualTo(i == 24);
        }
        transactions.executeWithoutResult(status -> badges.checkAndAwardBadges(author));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_badge WHERE user_id = ? AND badge_code = 'remix_king'",
                Long.class, author.getId())).isEqualTo(1);
    }
}
