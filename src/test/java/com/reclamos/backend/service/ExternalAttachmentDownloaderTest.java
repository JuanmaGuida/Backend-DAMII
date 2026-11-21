package com.reclamos.backend.service;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalAttachmentDownloaderTest {
    private static final URI FILE = URI.create("https://files.example.test/photo.png?ticket=synthetic");
    private final CloseableHttpClient client = mock(CloseableHttpClient.class);
    private final ExternalAttachmentDownloader downloader =
            new ExternalAttachmentDownloader(Set.of("files.example.test"), client);

    @AfterEach void close() {
        downloader.close();
    }

    @Test void readsTheCompleteBoundedResponse() throws Exception {
        response(200, new byte[]{1, 2, 3});
        assertThat(downloader.download(FILE, 3, 3L)).hasValueSatisfying(bytes ->
                assertThat(bytes).containsExactly(1, 2, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://files.example.test/a", "https://other.example.test/a",
            "https://files.example.test:8443/a", "https://files.example.test/a#fragment",
            "https://user@files.example.test/a"})
    void rejectsUnapprovedUrlsBeforeOpeningAConnection(String url) {
        assertThat(downloader.download(URI.create(url), 20, null)).isEmpty();
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 403, 404, 410})
    void unavailableOrRedirectedFilesAreNotStored(int code) throws Exception {
        response(code, new byte[]{1});
        assertThat(downloader.download(FILE, 10, null)).isEmpty();
        verify(client).executeOpen(isNull(), any(), isNull());
    }

    @ParameterizedTest @ValueSource(ints = {408, 503})
    void transientResponseRetriesAtMostOnceThenLeavesOnlyTheReference(int code) throws Exception {
        response(code, null);
        assertThat(downloader.download(FILE, 10, null)).isEmpty();
        verify(client, times(2)).executeOpen(isNull(), any(), isNull());
    }

    @Test void canRecoverOnTheSecondAttempt() throws Exception {
        var ok = new BasicClassicHttpResponse(200);
        ok.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[]{1}), -1, ContentType.APPLICATION_OCTET_STREAM));
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(new BasicClassicHttpResponse(503), ok);
        assertThat(downloader.download(FILE, 10, null)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1));
    }

    @Test void doesNotRetryRateLimitingOrIgnoreARetryAfterHeader() throws Exception {
        var busy = new BasicClassicHttpResponse(503);
        busy.addHeader("Retry-After", "60");
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(new BasicClassicHttpResponse(429), busy);
        assertThat(downloader.download(FILE, 10, null)).isEmpty();
        assertThat(downloader.download(FILE, 10, null)).isEmpty();
        verify(client, times(2)).executeOpen(isNull(), any(), isNull());
    }

    @Test
    @Timeout(10)
    void stalledDownloadExhaustsTheDeadlineWithoutRetrying() throws Exception {
        when(client.executeOpen(isNull(), any(), isNull())).thenAnswer(call -> {
            new CountDownLatch(1).await();
            return null;
        });
        assertThat(downloader.download(FILE, 10, null)).isEmpty();
        verify(client).executeOpen(isNull(), any(), isNull());
    }

    @Test void rejectsOversizedStreamsAndSizeMismatches() throws Exception {
        response(200, new byte[]{1, 2, 3});
        assertThat(downloader.download(FILE, 2, null)).isEmpty();
        response(200, new byte[]{1, 2, 3});
        assertThat(downloader.download(FILE, 5, 4L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.0.1",
            "100.64.0.1", "0.0.0.0", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1",
            "64:ff9b::1", "2001:db8::1", "2002::1"})
    void deniesNonPublicAddresses(String address) throws UnknownHostException {
        assertThat(ExternalAttachmentDownloader.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @Test void resolverRejectsLoopbackEvenForANameOnItsAllowlist() {
        assertThatThrownBy(() -> ExternalAttachmentDownloader.publicDns(Set.of("localhost")).resolve("localhost"))
                .isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.example.test", "192.0.2.1", "localhost", ".example.test", "example.test.",
            "files..example.test", "-files.example.test", "files-.example.test", "file_store.example.test"})
    void malformedAllowlistFailsClosed(String host) {
        assertThatThrownBy(() -> new ExternalAttachmentDownloader(host))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void enforcesDnsHostnameAndLabelLengthLimits() {
        String label = "a".repeat(63);
        String host = String.join(".", label, label, label, "b".repeat(61));
        String allowedHosts = host.toUpperCase(Locale.ROOT) + ", files-2.example.test";
        assertThatCode(() -> new ExternalAttachmentDownloader(allowedHosts).close())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ExternalAttachmentDownloader(host + "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalAttachmentDownloader(label + "a.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void response(int code, byte[] body) throws Exception {
        var response = new BasicClassicHttpResponse(code);
        if (body != null) {
            response.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), -1, ContentType.APPLICATION_OCTET_STREAM));
        }
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response);
    }
}
