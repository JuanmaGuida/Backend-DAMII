package com.reclamos.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Determina la dirección del cliente que originó un request.
 *
 * <p>{@code X-Forwarded-For} sólo se respeta cuando la conexión TCP inmediata
 * proviene de un proxy autorizado, es decir, una dirección incluida en la lista
 * de redes de confianza ({@code tracking.rate-limit.trusted-proxies}). Cualquier
 * otro par, incluso uno de red privada que no figure en la lista, se identifica
 * por su propia dirección: así un cliente no puede falsear su origen con ese
 * header ni variar la clave del limitador.</p>
 *
 * <p>Por defecto la lista abarca loopback y las redes privadas, que es donde
 * llega el proxy del frontend dentro del despliegue. Para un entorno donde el
 * proxy tiene una dirección conocida conviene acotarla a esa red.</p>
 */
@Component
public class ClientAddressResolver {
    /** Loopback, redes privadas, enlace local y direcciones locales únicas IPv6. */
    static final List<String> DEFAULT_TRUSTED_PROXIES = List.of(
            "127.0.0.0/8", "::1/128",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "fe80::/10", "fc00::/7");

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressResolver() {
        this(DEFAULT_TRUSTED_PROXIES);
    }

    @Autowired
    public ClientAddressResolver(
            @Value("${tracking.rate-limit.trusted-proxies:127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,"
                    + "192.168.0.0/16,169.254.0.0/16,fe80::/10,fc00::/7}") List<String> trustedProxies) {
        // Una red mal escrita debe impedir el arranque, no dejar de proteger en silencio.
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(network -> !network.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isTrustedProxy(peer)) {
            return peer;
        }
        String forwardedFor = request.getHeader(FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        // El último salto es el que agregó el proxy confiable más cercano.
        String candidate = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).trim();
        return isIpLiteral(candidate) ? candidate : peer;
    }

    private boolean isTrustedProxy(String address) {
        if (!isIpLiteral(address)) {
            return false;
        }
        try {
            return trustedProxies.stream().anyMatch(network -> network.matches(address));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isIpLiteral(String address) {
        if (address == null || !IP_LITERAL.matcher(address).matches()) return false;
        if (address.indexOf(':') >= 0) {
            try {
                // A colon plus the restricted alphabet permits only IPv6 parsing, never a DNS hostname.
                InetAddress.getByName(address);
                return true;
            } catch (UnknownHostException invalid) {
                return false;
            }
        }
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(character -> character >= '0' && character <= '9')
                    || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
