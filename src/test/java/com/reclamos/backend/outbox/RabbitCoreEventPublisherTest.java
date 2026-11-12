package com.reclamos.backend.outbox;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.reclamos.backend.outbox.PublicationFailure.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitCoreEventPublisherTest {
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final RabbitCoreEventPublisher publisher = new RabbitCoreEventPublisher(factory, OutboxFixtures.settings(Set.of()));
    private final UUID id = UUID.randomUUID();
    private final byte[] body = "{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception {
        when(factory.newConnection("m2-core-outbox")).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);
    }
    @AfterEach void resetInterrupt() { Thread.interrupted(); }

    @Test void publishesPersistentMandatoryMessageWithValidatedUserIdAndPreservesPayload() throws Exception {
        publisher.publish(id, "ticketCreated", body);
        var properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel);
        order.verify(channel).exchangeDeclarePassive("muni.inbox");
        order.verify(channel).confirmSelect();
        order.verify(channel).addReturnListener(any(ReturnCallback.class));
        order.verify(channel).basicPublish(eq("muni.inbox"), eq(""), eq(true), properties.capture(), same(body));
        order.verify(channel).waitForConfirms(2000);
        assertThat(properties.getValue().getUserId()).isEqualTo("synthetic-module-user");
        assertThat(properties.getValue().getAppId()).isEqualTo("atencion-ciudadana");
        assertThat(properties.getValue().getMessageId()).isEqualTo(id.toString());
        assertThat(properties.getValue().getContentType()).isEqualTo("application/json");
        assertThat(properties.getValue().getDeliveryMode()).isEqualTo(2);
        verify(connection).abort(1000);
    }
    @Test void nackIsNeverReportedAsPublished() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        assertFailure(BROKER_NACK);
    }
    @Test void mandatoryReturnWinsOverPositiveBrokerConfirm() throws Exception {
        doAnswer(invocation -> {
            ((ReturnCallback) invocation.getArgument(0)).handle(new Return(312, "private-return-text", "muni.inbox", "", null, body));
            return null;
        }).when(channel).addReturnListener(any(ReturnCallback.class));
        assertFailure(UNROUTABLE);
    }
    @Test void timeoutIsAmbiguousAndRetryable() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException("private"));
        assertFailure(CONFIRM_TIMEOUT);
    }
    @Test void ioFailureDoesNotLeakBrokerMessageOrCause() throws Exception {
        doThrow(new IOException("synthetic-password private-payload")).when(channel)
                .basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
        assertFailure(BROKER_UNAVAILABLE);
    }
    @Test void interruptIsRestoredAndNeverAcknowledgedAsSuccess() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenThrow(new InterruptedException("private"));
        assertFailure(INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    @Test void connectionFailureNeverPublishesOrCreatesTopology() throws Exception {
        when(factory.newConnection("m2-core-outbox")).thenThrow(new IOException("private-password"));
        assertFailure(BROKER_UNAVAILABLE);
        verifyNoInteractions(channel);
    }
    @Test void connectionTimeoutIsAvailabilityFailureRatherThanAConfirmTimeout() throws Exception {
        when(factory.newConnection("m2-core-outbox")).thenThrow(new TimeoutException("private"));
        assertFailure(BROKER_UNAVAILABLE);
    }
    @Test void cleanupFailureDoesNotUndoAnAlreadyReceivedConfirm() {
        doThrow(new IllegalStateException("private")).when(connection).abort(anyInt());
        assertThatCode(() -> publisher.publish(id, "ticketCreated", body)).doesNotThrowAnyException();
    }
    private void assertFailure(PublicationFailure.Code code) {
        assertThatThrownBy(() -> publisher.publish(id, "ticketUpdated", body))
                .isInstanceOfSatisfying(PublicationFailure.class, failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage(code.name()).hasNoCause();
    }
}
