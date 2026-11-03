package com.reclamos.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Determina la dirección del cliente que originó un request.
 *
 * <p>Detrás del proxy del frontend la conexión TCP llega siempre desde la red
 * privada del despliegue, por lo que {@code X-Forwarded-For} sólo se respeta
 * cuando el par inmediato es loopback o una dirección privada. Un cliente que
 * llegue directo desde una red pública no puede falsear su dirección con ese
 * header.</p>
 */
@Component
public class ClientAddressResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isPrivatePeer(peer)) {
            return peer;
        }
        String forwardedFor = request.getHeader(FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        // El último salto es el que agregó el proxy confiable más cercano.
        String[] hops = forwardedFor.split(",");
        String candidate = hops[hops.length - 1].trim();
        return IP_LITERAL.matcher(candidate).matches() ? candidate : peer;
    }

    private boolean isPrivatePeer(String address) {
        try {
            InetAddress ip = InetAddress.getByName(address);
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()
                    || isUniqueLocalIpv6(ip);
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress ip) {
        return ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc;
    }
}
