package com.tramo.backend.subscription.patreon;

import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PatreonConfigurationTest {
    private PatreonWebhookController controller(String secret, Environment environment) {
        return new PatreonWebhookController(secret, mock(UserRepository.class), mock(SubscriptionService.class),
                new ObjectMapper(), mock(PatreonWebhookSignatureRepository.class), mock(PlatformTransactionManager.class), environment);
    }

    @Test
    void productionRequiresANonBlankSecret() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> controller("", environment)).isInstanceOf(IllegalStateException.class).hasMessageContaining("PATREON_WEBHOOK_SECRET");
        assertThatThrownBy(() -> controller("  ", environment)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> controller("configured-secret", environment)).doesNotThrowAnyException();
    }

    @Test
    void localWithoutASecretStillRejectsWebhooks() {
        assertThat(controller("", new MockEnvironment()).handle("{}", "invalid", "members:pledge:create").getStatusCode().value()).isEqualTo(403);
    }
}
