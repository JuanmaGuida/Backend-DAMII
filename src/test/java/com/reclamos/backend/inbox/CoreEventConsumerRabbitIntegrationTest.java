package com.reclamos.backend.inbox;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.reclamos.backend.inbox.IncomingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** AMQP protocol evidence on an ephemeral broker, NOT evidence of Core's retry/DLQ policy. */
class CoreEventConsumerRabbitIntegrationTest {
    private static GenericContainer<?> rabbit;

    @BeforeAll static void startBroker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required for isolated RabbitMQ test");
        rabbit = new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-alpine"))
                .withEnv("RABBITMQ_DEFAULT_USER", "int05")
                .withEnv("RABBITMQ_DEFAULT_PASS", "int05-disposable")
                .withExposedPorts(5672)
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        rabbit.start();
    }

    @AfterAll static void stopBroker() { if (rabbit != null) rabbit.stop(); }

    @ParameterizedTest @ValueSource(strings = {"valid", "invalid", "transient"})
    void realBrokerReceivesAckOrDeadLettersNackWithoutRequeue(String scenario) throws Exception {
        ConnectionFactory isolated = new ConnectionFactory();
        isolated.setHost(rabbit.getHost()); isolated.setPort(rabbit.getMappedPort(5672));
        isolated.setUsername("int05"); isolated.setPassword("int05-disposable");
        isolated.setAutomaticRecoveryEnabled(false);
        Connection realConnection = isolated.newConnection();
        try (Connection adminConnection = isolated.newConnection(); Channel admin = adminConnection.createChannel()) {
            Channel realChannel = realConnection.createChannel();
            // Test fixture topology exists ONLY inside this disposable container.
            String deadExchange = "int05.dead", deadQueue = "int05.failed";
            admin.exchangeDeclare(deadExchange, "fanout", false, true, null);
            admin.queueDeclare(deadQueue, false, false, true, null);
            admin.queueBind(deadQueue, deadExchange, "");
            admin.queueDeclare(CoreEventConsumer.QUEUE, false, false, true,
                    Map.of("x-dead-letter-exchange", deadExchange));
            CoreInboxProcessor processor = mock(CoreInboxProcessor.class);
            var validator = new CoreIncomingEventValidator();
            doAnswer(call -> {
                validator.decode(call.getArgument(0));
                if (scenario.equals("transient")) throw new DataAccessResourceFailureException("synthetic");
                return null;
            }).when(processor).process(any());
            var settled = new CompletableFuture<String>();
            Channel observed = mock(Channel.class, org.mockito.AdditionalAnswers.delegatesTo(realChannel));
            doAnswer(call -> {
                realChannel.basicAck(call.getArgument(0), call.getArgument(1));
                settled.complete("ACK"); return null;
            }).when(observed).basicAck(anyLong(), anyBoolean());
            doAnswer(call -> {
                realChannel.basicNack(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                settled.complete("NACK"); return null;
            }).when(observed).basicNack(anyLong(), anyBoolean(), anyBoolean());
            Connection owned = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(realConnection));
            doReturn(observed).when(owned).createChannel();
            ConnectionFactory adapter = mock(ConnectionFactory.class);
            when(adapter.newConnection("m2-core-inbox")).thenReturn(owned);
            var consumer = new CoreEventConsumer(adapter, processor, 1);
            try {
                consumer.connectIfNeeded();
                byte[] body = scenario.equals("invalid") ? "{private-invalid-json".getBytes(StandardCharsets.UTF_8)
                        : bytes(citizen(UUID.randomUUID(), "REGISTERED"));
                admin.basicPublish("", CoreEventConsumer.QUEUE, null, body);
                assertThat(settled.get(10, TimeUnit.SECONDS)).isEqualTo(scenario.equals("valid") ? "ACK" : "NACK");
                if (scenario.equals("valid")) {
                    verify(observed, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
                    assertThat(realChannel.queueDeclarePassive(CoreEventConsumer.QUEUE).getMessageCount()).isZero();
                    assertThat(admin.basicGet(deadQueue, true)).isNull();
                } else {
                    verify(observed).basicNack(anyLong(), eq(false), eq(false));
                    verify(observed, never()).basicAck(anyLong(), anyBoolean());
                    GetResponse failed = null;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (failed == null && System.nanoTime() < deadline) {
                        failed = admin.basicGet(deadQueue, true);
                        if (failed == null) Thread.sleep(20);
                    }
                    assertThat(failed).isNotNull(); assertThat(failed.getBody()).isEqualTo(body);
                    var deaths = (java.util.List<?>) failed.getProps().getHeaders().get("x-death");
                    assertThat(((Map<?, ?>) deaths.getFirst()).get("reason").toString()).isEqualTo("rejected");
                    assertThat(admin.queueDeclarePassive(CoreEventConsumer.QUEUE).getMessageCount()).isZero();
                }
                verify(processor, times(1)).process(any());
            } finally {
                // Admin has its own connection: queue deletion can cancel/abort the consumer.
                admin.queueDelete(CoreEventConsumer.QUEUE); admin.queueDelete(deadQueue);
                consumer.close();
            }
        } finally { realConnection.abort(); }
    }
}
