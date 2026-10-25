package com.tramo.backend.auth;

import com.tramo.backend.auth.service.CaptchaVerifier;
import com.tramo.backend.auth.service.IdentityRateLimiter;
import com.tramo.backend.exception.CaptchaVerificationException;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LoginRateLimiterTest {
    @Test
    void hardLimitAppliesOnlyToTheFailingIpAndUsername() {
        var limiter = new IdentityRateLimiter(new RateLimiterService());
        var captcha = mock(CaptchaVerifier.class);
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("victim", "1.2.3.4", null, captcha);
            limiter.recordLoginFailure("victim", "1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.checkLogin("VICTIM", "1.2.3.4", null, captcha))
                .isInstanceOf(CaptchaVerificationException.class);
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("victim", "1.2.3.4", "valid", captcha);
            limiter.recordLoginFailure("victim", "1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.checkLogin("victim", "1.2.3.4", "valid", captcha))
                .isInstanceOf(LimitExceededException.class);
        assertThatCode(() -> limiter.checkLogin("victim", "5.6.7.8", "valid", captcha)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLogin("other", "1.2.3.4", null, captcha)).doesNotThrowAnyException();
        verify(captcha, times(6)).verify("valid", "login");
    }
}
