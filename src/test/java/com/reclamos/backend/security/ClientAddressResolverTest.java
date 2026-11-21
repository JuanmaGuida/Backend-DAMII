package com.reclamos.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig({ClientAddressResolver.class, ClientAddressResolverTest.ConversionConfig.class})
@TestPropertySource("classpath:application.properties")
class ClientAddressResolverTest {
    @Autowired
    private ClientAddressResolver resolver;

    @Configuration(proxyBeanMethods = false)
    static class ConversionConfig {
        @Bean
        static ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }
    }

    @Test
    void usesTheForwardedAddressWhenThePeerIsAPrivateProxy() {
        assertEquals("198.51.100.7", resolve("172.18.0.5", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("10.0.0.4", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("192.168.1.20", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("127.0.0.1", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("169.254.1.2", "198.51.100.7"));
        assertEquals("2001:db8::7", resolve("::1", "2001:db8::7"));
        assertEquals("2001:db8::7", resolve("fe80::1", "2001:db8::7"));
        assertEquals("2001:db8::7", resolve("fd00::5", "2001:db8::7"));
    }

    @Test
    void ignoresTheForwardedAddressWhenThePeerIsPublic() {
        assertEquals("203.0.113.9", resolve("203.0.113.9", "198.51.100.7"));
    }

    @Test
    void takesTheHopAddedByTheClosestProxy() {
        assertEquals("198.51.100.7", resolve("172.18.0.5", "192.0.2.1, 198.51.100.7"));
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsMissingBlankOrMalformed() {
        assertEquals("172.18.0.5", resolve("172.18.0.5", null));
        assertEquals("172.18.0.5", resolve("172.18.0.5", "  "));
        assertEquals("172.18.0.5", resolve("172.18.0.5", "not-an-ip; DROP"));
    }

    @Test
    void rejectsEmptyFinalHopsAndInvalidIpLiteralsWithoutTrustingEarlierValues() {
        for (String header : List.of(",", ",,,", "198.51.100.7,", "198.51.100.7,  ",
                "face", "dead.beef", "1.2.3", "999.1.2.3", "1..2.3", "0000.1.2.3", "::::")) {
            assertEquals("172.18.0.5", resolve("172.18.0.5", header), header);
        }
        assertEquals("2001:db8::7", resolve("172.18.0.5", "forged, 2001:db8::7"));
        assertEquals("::ffff:198.51.100.7", resolve("172.18.0.5", "::ffff:198.51.100.7"));
    }

    @Test
    void doesNotResolveHostnamesOrTrustMalformedPeers() {
        for (String peer : List.of("face", "dead.beef", "::::", "999.1.2.3")) {
            assertEquals(peer, resolve(peer, "198.51.100.7"));
        }
    }

    @Test
    void onlyTrustsTheConfiguredProxyNetworks() {
        ClientAddressResolver narrow = new ClientAddressResolver(List.of("172.18.0.0/16"));

        assertEquals("198.51.100.7", resolve(narrow, "172.18.0.5", "198.51.100.7"));
        // Una red privada que no figura en la lista deja de ser confiable.
        assertEquals("10.0.0.4", resolve(narrow, "10.0.0.4", "198.51.100.7"));
        assertEquals("127.0.0.1", resolve(narrow, "127.0.0.1", "198.51.100.7"));
    }

    @Test
    void anEmptyListNeverTrustsTheForwardedHeader() {
        ClientAddressResolver none = new ClientAddressResolver(List.of());

        assertEquals("172.18.0.5", resolve(none, "172.18.0.5", "198.51.100.7"));
    }

    @Test
    void rejectsAMalformedNetworkAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver(List.of("no-es-una-red")));
    }

    private String resolve(String peer, String forwardedFor) {
        return resolve(resolver, peer, forwardedFor);
    }

    private String resolve(ClientAddressResolver target, String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return target.resolve(request);
    }
}
