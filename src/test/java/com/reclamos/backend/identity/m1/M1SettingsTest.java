package com.reclamos.backend.identity.m1;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class M1SettingsTest {
    @Test
    void requiresHttpsOriginsAndNonemptyDecodedSecretWithoutPrintingIt() {
        String invalidSecret = "private-invalid-material";
        assertThatThrownBy(() -> settings(M1TestTokens.BASE, invalidSecret, M1TestTokens.ORIGIN))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(invalidSecret).hasNoCause();
        assertThatThrownBy(() -> settings(M1TestTokens.BASE, "YQ==", M1TestTokens.ORIGIN))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        for (String origin : new String[]{"", "null", "http://m2.example.test", "https://user:pass@m2.example.test",
                "https://m2.example.test/path", "https://m2.example.test?query", "https://m2.example.test#fragment"}) {
            assertThatThrownBy(() -> settings(M1TestTokens.BASE, M1TestTokens.encodedKey(), origin))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> settings("http://m1.example.test", M1TestTokens.encodedKey(), M1TestTokens.ORIGIN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalizesOriginsAndRejectsUnboundedTimeouts() {
        assertThat(settings(M1TestTokens.BASE, M1TestTokens.encodedKey(), "https://M2.example.test:443/").browserOrigin())
                .isEqualTo(M1TestTokens.ORIGIN);
        assertThatThrownBy(() -> new M1Settings(M1TestTokens.BASE, M1TestTokens.encodedKey(), M1TestTokens.ORIGIN,
                Duration.ZERO, Duration.ofSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new M1Settings(M1TestTokens.BASE, M1TestTokens.encodedKey(), M1TestTokens.ORIGIN,
                Duration.ofSeconds(1), Duration.ofSeconds(-1))).isInstanceOf(IllegalStateException.class);
    }

    private M1Settings settings(String base, String key, String origin) {
        return new M1Settings(base, key, origin, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}
