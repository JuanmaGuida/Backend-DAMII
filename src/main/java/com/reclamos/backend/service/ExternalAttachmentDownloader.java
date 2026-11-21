package com.reclamos.backend.service;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class ExternalAttachmentDownloader {
    private static final int DEADLINE_SECONDS = 5;
    private static final Timeout IO_TIMEOUT = Timeout.ofSeconds(DEADLINE_SECONDS);

    private final Set<String> hosts;
    private final CloseableHttpClient client;
    // A stuck OS resolver cannot hold up Inbox indefinitely or accumulate queued downloads.
    private final ExecutorService workers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("external-attachment-", 0).factory());

    @Autowired
    public ExternalAttachmentDownloader(@Value("${attachment.external.allowed-hosts:}") String allowedHosts) {
        this(parseHosts(allowedHosts), null);
    }

    ExternalAttachmentDownloader(Set<String> hosts, CloseableHttpClient client) {
        this.hosts = Set.copyOf(hosts);
        this.client = client == null ? createClient(this.hosts) : client;
    }

    public Optional<byte[]> download(URI uri, long maximum, Long expectedSize) {
        if (!canDownload(uri) || maximum <= 0
                || (expectedSize != null && (expectedSize <= 0 || expectedSize > maximum))) {
            return Optional.empty();
        }
        int limit = (int) Math.min(maximum, Integer.MAX_VALUE - 1L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        for (int attempt = 0; attempt < 2 && deadline - System.nanoTime() > 0; attempt++) {
            var request = new HttpGet(uri);
            Future<Optional<byte[]>> pending;
            try {
                pending = workers.submit(() -> fetch(request, limit, expectedSize));
            } catch (RejectedExecutionException busy) {
                return Optional.empty();
            }
            try {
                return pending.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("EXTERNAL_ATTACHMENT_INTERRUPTED", interrupted);
            } catch (TimeoutException timeout) {
                return Optional.empty();
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof IOException)) {
                    throw new IllegalStateException("EXTERNAL_ATTACHMENT_DOWNLOAD_FAILED", failure.getCause());
                }
                // An optional copy gets one retry within the original deadline.
            } finally {
                if (!pending.isDone()) {
                    request.cancel();
                    pending.cancel(true);
                }
            }
        }
        return Optional.empty();
    }

    private boolean canDownload(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && hosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private Optional<byte[]> fetch(HttpGet request, int limit, Long expectedSize) throws IOException {
        try (var response = client.executeOpen(null, request, null)) {
            try {
                return readResponse(response, limit, expectedSize);
            } finally {
                // Abort before close so a rejected response is not drained from the network.
                request.cancel();
            }
        } catch (UnknownHostException | SSLHandshakeException unavailable) {
            return Optional.empty();
        }
    }

    private Optional<byte[]> readResponse(ClassicHttpResponse response, int limit, Long expectedSize) throws IOException {
        int code = response.getCode();
        if ((code == 408 || code == 500 || code == 502 || code == 503 || code == 504)
                && response.getFirstHeader("Retry-After") == null) {
            throw new IOException("EXTERNAL_ATTACHMENT_RETRYABLE_RESPONSE");
        }
        var entity = response.getEntity();
        if (code != 200 || entity == null || entity.getContentLength() > limit) {
            return Optional.empty();
        }
        var encoding = response.getFirstHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue())) {
            return Optional.empty();
        }
        byte[] bytes = entity.getContent().readNBytes(limit + 1);
        if (bytes.length == 0 || bytes.length > limit || (expectedSize != null && bytes.length != expectedSize)) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    private static Set<String> parseHosts(String allowedHosts) {
        return Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT))
                .map(host -> {
                    if (!host.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                            + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
                            || host.chars().allMatch(c -> c == '.' || Character.isDigit(c))) {
                        throw new IllegalArgumentException("Los hosts de adjuntos externos deben ser DNS exactos.");
                    }
                    return host;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static CloseableHttpClient createClient(Set<String> allowedHosts) {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(publicDns(allowedHosts))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(IO_TIMEOUT).setSocketTimeout(IO_TIMEOUT).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(IO_TIMEOUT).setResponseTimeout(IO_TIMEOUT).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }

    static DnsResolver publicDns(Set<String> allowedHosts) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                requireAllowed(host);
                InetAddress[] addresses = InetAddress.getAllByName(host);
                // These exact addresses are passed to the connector; it does not resolve the name again.
                if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
                    throw new BlockedDestination();
                }
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                requireAllowed(host);
                return host;
            }

            private void requireAllowed(String host) throws BlockedDestination {
                if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                    throw new BlockedDestination();
                }
            }
        };
    }

    static boolean isPublic(InetAddress address) {
        byte[] raw = address.getAddress();
        int first = Byte.toUnsignedInt(raw[0]);
        int second = Byte.toUnsignedInt(raw[1]);
        int third = Byte.toUnsignedInt(raw[2]);
        if (raw.length == 4) {
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2))
                        || (second == 88 && third == 99)))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(first == 203 && second == 0 && third == 113);
        }
        // Global unicast only; also reject transition, documentation and protocol-assignment ranges.
        return (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && (third < 2
                    || (third == 0x0d && Byte.toUnsignedInt(raw[3]) == 0xb8)))
                && !(first == 0x3f && second == 0xff && third < 0x10);
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }

    private static final class BlockedDestination extends UnknownHostException { }
}
