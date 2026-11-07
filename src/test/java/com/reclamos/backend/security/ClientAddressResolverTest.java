package com.reclamos.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAddressResolverTest {
    private final ClientAddressResolver resolver = new ClientAddressResolver();

    @Test
    void usesTheForwardedAddressWhenThePeerIsAPrivateProxy() {
        assertEquals("198.51.100.7", resolve("172.18.0.5", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("10.0.0.4", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("192.168.1.20", "198.51.100.7"));
        assertEquals("198.51.100.7", resolve("127.0.0.1", "198.51.100.7"));
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
