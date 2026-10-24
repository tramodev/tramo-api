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
    void forkProgressBelongsToOriginalAuthor() {
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
    }
}
