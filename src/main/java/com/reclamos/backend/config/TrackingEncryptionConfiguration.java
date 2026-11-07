package com.reclamos.backend.config;

import com.reclamos.backend.service.TrackingCodeCipher;
import com.reclamos.backend.service.TrackingCodeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class TrackingEncryptionConfiguration {
    @Bean
    TrackingCodeCipher trackingCodeCipher(Environment environment, TrackingCodeService trackingCodes) {
        // Validate in the cipher instead of a binding error that may print a rejected secret.
        return new TrackingCodeCipher(
                environment.getProperty("app.tracking.encryption.key-base64", ""),
                environment.getProperty("app.tracking.encryption.key-id", ""), trackingCodes);
    }
}
