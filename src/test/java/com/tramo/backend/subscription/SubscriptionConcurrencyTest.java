package com.tramo.backend.subscription;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.subscription.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionConcurrencyTest extends AbstractIntegrationTest {
    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subscriptionUpdatesWaitForTheSameUserTransaction(boolean cancellation) throws Exception {
        var user = createUser("concurrentsubscriber");
        var plan = subscriptions.findOrCreateSupporterPlan();
        subscriptions.activateSupporterSubscription(user, plan);
        subscriptions.deactivateSupporterSubscription(user);
        var activated = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                subscriptions.activateSupporterSubscription(user, plan);
                activated.countDown();
                release.orTimeout(10, TimeUnit.SECONDS).join();
            }));
            assertThat(activated.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                if (cancellation) subscriptions.deactivateSupporterSubscription(user);
                else subscriptions.activateSupporterSubscription(user, plan);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.complete(null);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.complete(null);
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, user.getId())).isEqualTo(cancellation ? 0L : 1L);
        subscriptions.deactivateSupporterSubscription(user);
        assertThat(subscriptions.isSupporter(user)).isFalse();
    }

    @Test
    void expiredActiveSubscriptionCanBeReactivated() {
        var user = createUser("expiredsubscriber");
        var plan = subscriptions.findOrCreateSupporterPlan();
        subscriptions.activateSupporterSubscription(user, plan);
        jdbcTemplate.update("UPDATE subscription SET end_date = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE user_id = ?", user.getId());

        subscriptions.activateSupporterSubscription(user, plan);

        assertThat(subscriptions.isSupporter(user)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ? AND status = 'CANCELED'",
                Long.class, user.getId())).isEqualTo(1L);
    }

    @Test
    void databaseRejectsASecondActiveSubscription() {
        var user = createUser("uniquesubscriber");
        subscriptions.activateSupporterSubscription(user, subscriptions.findOrCreateSupporterPlan());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO subscription (id, user_id, plan_id, status, start_date)
                SELECT -1, user_id, plan_id, status, start_date FROM subscription WHERE user_id = ?
                """, user.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationCancelsDuplicatesAndExpiredRowsWithoutDeletingHistory() {
        var user = createUser("duplicatesubscriber");
        subscriptions.activateSupporterSubscription(user, subscriptions.findOrCreateSupporterPlan());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("DROP INDEX idx_subscription_active_user");
            jdbcTemplate.update("""
                    INSERT INTO subscription (id, user_id, plan_id, status, start_date, end_date)
                    SELECT -1, user_id, plan_id, status, start_date, CURRENT_TIMESTAMP + INTERVAL '1 day'
                    FROM subscription WHERE user_id = ?
                    """, user.getId());
            jdbcTemplate.update("""
                    INSERT INTO subscription (id, user_id, plan_id, status, start_date, end_date)
                    SELECT -2, user_id, plan_id, status, start_date, CURRENT_TIMESTAMP - INTERVAL '1 day'
                    FROM subscription WHERE user_id = ? AND id <> -1
                    """, user.getId());
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V36__unique_active_subscription.sql"));
                return null;
            });

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ?",
                    Long.class, user.getId())).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE' AND end_date IS NULL",
                    Long.class, user.getId())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ? AND status = 'CANCELED'",
                    Long.class, user.getId())).isEqualTo(2L);
            status.setRollbackOnly();
        });
    }
}
