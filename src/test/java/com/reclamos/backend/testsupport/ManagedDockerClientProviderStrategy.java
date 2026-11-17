package com.reclamos.backend.testsupport;

import com.github.dockerjava.api.DockerClient;
import org.testcontainers.shaded.com.github.dockerjava.core.DefaultDockerClientConfig;
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.utility.TestcontainersConfiguration;

/** Uses Boot-managed HttpComponents instead of the vulnerable copies in zerodep. */
public final class ManagedDockerClientProviderStrategy extends DockerClientProviderStrategy {
    private final org.testcontainers.shaded.com.github.dockerjava.core.DockerClientConfig config;
    private DockerClient client;

    public ManagedDockerClientProviderStrategy() {
        var builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        var settings = TestcontainersConfiguration.getInstance();
        String host = settings.getEnvVarOrUserProperty("docker.host", null);
        String tls = settings.getEnvVarOrUserProperty("docker.tls.verify", null);
        String certificates = settings.getEnvVarOrUserProperty("docker.cert.path", null);
        if (host != null) builder.withDockerHost(host);
        if (tls != null) builder.withDockerTlsVerify(tls);
        if (certificates != null) builder.withDockerCertPath(certificates);
        config = builder.withApiVersion("1.44").build();
    }

    @Override
    public TransportConfig getTransportConfig() {
        return TransportConfig.builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
    }

    @Override
    public synchronized DockerClient getDockerClient() {
        if (client == null) {
            var transport = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
            client = DockerClientImpl.getInstance(config, transport);
        }
        return client;
    }

    @Override
    public String getDescription() {
        return "Boot-managed HttpClient5 at " + config.getDockerHost();
    }

    @Override
    protected int getPriority() { return 1000; }

    @Override
    protected boolean isPersistable() { return false; }
}
