package com.reclamos.backend.ai;

import com.reclamos.backend.ai.AiTestSupport.MutableClock;
import com.reclamos.backend.security.ClientAddressResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRateLimiterTest {
    AiProperties properties = AiTestSupport.enabledProperties();
    MutableClock clock = new MutableClock();
    AiRateLimiter limiter = new AiRateLimiter(properties, new ClientAddressResolver(), clock);

    @BeforeEach
    void setUp() {
        properties.setMaxRequestsPerClient(2);
        properties.setRateLimitWindow(Duration.ofMinutes(10));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    void callingFrom(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void blocksAClientThatExhaustsItsQuotaAndTellsHowLongToWait() {
        callingFrom("203.0.113.9");
        limiter.assertAllowed("classify");
        limiter.assertAllowed("classify");

        assertThatThrownBy(() -> limiter.assertAllowed("classify"))
                .isInstanceOfSatisfying(TooManyAiRequestsException.class,
                        blocked -> assertThat(blocked.getRetryAfterSeconds()).isBetween(1L, 600L));
    }

    @Test
    void otherClientsAndOtherFeaturesKeepTheirOwnQuota() {
        callingFrom("203.0.113.9");
        limiter.assertAllowed("classify");
        limiter.assertAllowed("classify");

        assertThatCode(() -> limiter.assertAllowed("insights")).doesNotThrowAnyException();
        callingFrom("203.0.113.10");
        assertThatCode(() -> limiter.assertAllowed("classify")).doesNotThrowAnyException();
    }

    @Test
    void quotaRecoversAfterTheWindow() {
        callingFrom("203.0.113.9");
        limiter.assertAllowed("classify");
        limiter.assertAllowed("classify");

        clock.advance(Duration.ofMinutes(11));

        assertThatCode(() -> limiter.assertAllowed("classify")).doesNotThrowAnyException();
    }

    @Test
    void forgedForwardedForFromAnUntrustedPeerDoesNotRotateTheKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        limiter.assertAllowed("classify");
        limiter.assertAllowed("classify");

        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "198.51.100.2");

        assertThatThrownBy(() -> limiter.assertAllowed("classify")).isInstanceOf(TooManyAiRequestsException.class);
    }

    @Test
    void doesNothingOutsideAnHttpRequest() {
        for (int i = 0; i < 10; i++) {
            limiter.assertAllowed("classify");
        }
    }
}
