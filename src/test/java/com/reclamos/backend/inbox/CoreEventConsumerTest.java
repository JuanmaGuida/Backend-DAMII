package com.reclamos.backend.inbox;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreEventConsumerTest {
    private final ConnectionFactory factory=mock(ConnectionFactory.class);
    private final CoreInboxProcessor processor=mock(CoreInboxProcessor.class);
    private final Connection connection=mock(Connection.class);
    private final Channel channel=mock(Channel.class);
    private final CoreEventConsumer consumer=new CoreEventConsumer(factory,processor,1);
    private final byte[] body={1,2,3};
    private Delivery delivery() { return new Delivery(new Envelope(17,true,"muni.bus","key"),new AMQP.BasicProperties(),body); }

    @Test void passiveQueueManualAckAndAckOnlyAfterProcessorReturns() throws Exception {
        when(factory.newConnection("m2-core-inbox")).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(connection.isOpen()).thenReturn(true); when(channel.isOpen()).thenReturn(true);
        consumer.connectIfNeeded(); consumer.connectIfNeeded();
        verify(factory,times(1)).newConnection("m2-core-inbox");
        verify(channel).queueDeclarePassive("q.atencion-ciudadana"); verify(channel).basicQos(1);
        var callback=ArgumentCaptor.forClass(DeliverCallback.class);
        verify(channel).basicConsume(eq(CoreEventConsumer.QUEUE),eq(false),callback.capture(),any(CancelCallback.class));
        doAnswer(i -> { verify(channel,never()).basicAck(anyLong(),anyBoolean()); return null; }).when(processor).process(body);
        callback.getValue().handle("consumer",delivery());
        var order=inOrder(processor,channel); order.verify(processor).process(body); order.verify(channel).basicAck(17,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        verify(channel,never()).queueDeclare(anyString(),anyBoolean(),anyBoolean(),anyBoolean(),any());
    }
    @Test void controlledAndTransientFailuresNackWithoutImmediateRequeue() throws Exception {
        doThrow(new IncomingEventFailure(IncomingEventFailure.Code.PREMATURE_PROGRESS),
                new DataAccessResourceFailureException("synthetic failure")).when(processor).process(body);
        consumer.receive(channel,connection,delivery()); consumer.receive(channel,connection,delivery());
        verify(channel,times(2)).basicNack(17,false,false); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void permanentBusinessFailuresAckAfterRollbackButSchemaAndVersionFailuresNack() throws Exception {
        doThrow(new com.reclamos.backend.exception.InvalidTicketRequestException("synthetic"),
                new com.reclamos.backend.exception.TicketStateConflictException("synthetic"),
                new IncomingEventFailure(IncomingEventFailure.Code.INVALID_EVENT),
                new IncomingEventFailure(IncomingEventFailure.Code.UNSUPPORTED_VERSION)).when(processor).process(body);
        for (int i=0;i<4;i++) consumer.receive(channel,connection,delivery());
        verify(channel,times(2)).basicAck(17,false); verify(channel,times(2)).basicNack(17,false,false);
    }
    @Test void rejectionAuditFailureNeverAcknowledgesAnUncommittedRejection() throws Exception {
        doThrow(new com.reclamos.backend.exception.InvalidTicketRequestException("synthetic")).when(processor).process(body);
        doThrow(new DataAccessResourceFailureException("synthetic")).when(processor).recordBusinessRejection(body);
        consumer.receive(channel,connection,delivery());
        verify(channel).basicNack(17,false,false); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void uncertainAckClosesOnlyOwningConnectionAndRedeliveryCanBeDeduplicated() throws Exception {
        doThrow(new IOException("connection lost")).when(channel).basicAck(17,false);
        consumer.receive(channel,connection,delivery()); verify(connection).abort(1000);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void connectAndQueueFailuresAreRetriedOnANewConnectionWithoutCreatingTopology() throws Exception {
        Connection recovered=mock(Connection.class); Channel recoveredChannel=mock(Channel.class);
        when(factory.newConnection("m2-core-inbox")).thenReturn(connection,recovered);
        when(connection.createChannel()).thenReturn(channel);
        doThrow(new IOException("queue unavailable")).when(channel).queueDeclarePassive(anyString());
        when(recovered.createChannel()).thenReturn(recoveredChannel);
        consumer.connectIfNeeded(); consumer.connectIfNeeded();
        verify(connection).abort(1000); verify(recoveredChannel).basicConsume(eq(CoreEventConsumer.QUEUE),eq(false),any(DeliverCallback.class),any(CancelCallback.class));
        consumer.close(); consumer.connectIfNeeded(); verify(recovered).abort(1000); verify(factory,times(2)).newConnection(anyString());
    }
    @Test void cancellationClosesTheOwningConnectionSoTheNextScanReconnects() throws Exception {
        when(factory.newConnection("m2-core-inbox")).thenReturn(connection); when(connection.createChannel()).thenReturn(channel);
        consumer.connectIfNeeded();
        var callback=ArgumentCaptor.forClass(CancelCallback.class);
        verify(channel).basicConsume(anyString(),eq(false),any(DeliverCallback.class),callback.capture());
        callback.getValue().handle("cancelled"); verify(connection).abort(1000);
        consumer.connectIfNeeded(); verify(factory,times(2)).newConnection(anyString());
    }
    @Test void failureLogsContainOnlyCodesEvenWhenExceptionsContainPrivatePayloads() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CoreEventConsumer.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        String privateValue = "private-dni-address-token-message";
        byte[] sensitive = privateValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var delivery = new Delivery(new Envelope(31, false, "", ""), new AMQP.BasicProperties(), sensitive);
        try {
            doThrow(new IncomingEventFailure(IncomingEventFailure.Code.INVALID_EVENT),
                    new IncomingEventFailure(IncomingEventFailure.Code.EVENT_ID_COLLISION),
                    new DataAccessResourceFailureException(privateValue),
                    new com.reclamos.backend.exception.InvalidTicketRequestException(privateValue),
                    new com.reclamos.backend.exception.TicketStateConflictException(privateValue))
                    .when(processor).process(sensitive);
            doThrow(new DataAccessResourceFailureException(privateValue)).doNothing()
                    .when(processor).recordBusinessRejection(sensitive);
            doThrow(new IOException(privateValue)).when(channel).basicAck(31, false);
            for (int i = 0; i < 5; i++) consumer.receive(channel, connection, delivery);
            assertThat(capture.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .contains("Core Inbox code=INVALID_EVENT", "Core Inbox code=EVENT_ID_COLLISION",
                            "Core Inbox code=PROCESSING_FAILED", "Core Inbox code=REJECTION_AUDIT_FAILED",
                            "Core Inbox code=ACK_CHANNEL_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(privateValue));
            assertThat(capture.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(channel, times(4)).basicNack(31, false, false);
        } finally {
            logger.detachAppender(capture); capture.stop();
        }
    }

    @Test void rejectsUnboundedPrefetch() {
        assertThatThrownBy(() -> new CoreEventConsumer(factory,processor,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CoreEventConsumer(factory,processor,17)).isInstanceOf(IllegalStateException.class);
    }

    @Test void channelCreationFailureAbortsTheOpenedConnectionAndAllowsReconnect() throws Exception {
        when(factory.newConnection("m2-core-inbox")).thenReturn(connection);
        when(connection.createChannel()).thenThrow(new IOException("synthetic channel failure")).thenReturn(channel);
        consumer.connectIfNeeded();
        verify(connection).abort(1000);
        consumer.connectIfNeeded();
        verify(factory,times(2)).newConnection("m2-core-inbox");
        verify(channel).basicConsume(eq(CoreEventConsumer.QUEUE),eq(false),any(DeliverCallback.class),any(CancelCallback.class));
        consumer.close();
        verify(connection,times(2)).abort(1000);
    }

    @Test void shutdownDuringProcessingClosesDeliveryChannelAndNeverReconnectsOrAcksOnAnotherChannel() throws Exception {
        when(factory.newConnection("m2-core-inbox")).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        consumer.connectIfNeeded();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(i -> {
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(processor).process(body);
        doThrow(new IOException("delivery connection closed during shutdown")).when(channel).basicAck(17, false);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> consumer.receive(channel, connection, delivery()));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                consumer.close();
                consumer.connectIfNeeded();
                verify(connection).abort(1000);
                verify(channel, never()).basicAck(anyLong(), anyBoolean());
                verify(factory, times(1)).newConnection(anyString());
            } finally {
                release.countDown();
            }
            inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(channel).basicAck(17, false); // Original channel only; broker may redeliver after the lost ACK.
        verify(connection, times(2)).abort(1000);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
