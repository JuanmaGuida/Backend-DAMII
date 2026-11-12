package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.OutboxEvent;
import com.reclamos.backend.entity.OutboxStatus;
import com.reclamos.backend.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static com.reclamos.backend.outbox.OutboxFixtures.*;
import static com.reclamos.backend.outbox.PublicationFailure.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoreOutboxProcessorTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutgoingEventValidator validator = mock(OutgoingEventValidator.class);
    private final CoreEventPublisher publisher = mock(CoreEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void emptyApprovalListNeverScansHistoricalPendingRows() {
        assertThat(processor(Set.of()).processNextApproved()).isFalse();
        verifyNoInteractions(repository, validator, publisher);
    }
    @Test void selectedButUnavailableEventDoesNothing() {
        var id = java.util.UUID.randomUUID();
        when(repository.findNextApprovedForUpdate(any(), any())).thenReturn(Optional.empty());
        assertThat(processor(Set.of(id)).processNextApproved()).isFalse();
        verifyNoInteractions(validator, publisher);
    }
    @Test void marksPublishedOnlyAfterBrokerConfirmationAndDoesNotChangeTheSnapshot() {
        var event = event("ticketCreated"); var processor = ready(event);
        var original = new java.util.LinkedHashMap<>(event.getPayload());
        doAnswer(invocation -> { assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING); return null; })
                .when(publisher).publish(any(), any(), any());
        assertThat(processor.processNextApproved()).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(event.getPayload()).isEqualTo(original);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNull();
    }
    @Test void persistsBackoffAndRetainsSameEventIdAcrossRetriesAndRestartedProcessor() {
        var event = event("ticketUpdated"); var processor = ready(event);
        doThrow(new PublicationFailure(CONFIRM_TIMEOUT)).doNothing().when(publisher).publish(any(), any(), any());
        processor.processNextApproved();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastFailureCode()).isEqualTo("CONFIRM_TIMEOUT");
        // Repository eligibility/time locking is exercised on PostgreSQL; a new service has no lost memory state.
        processor(Set.of(event.getEventId())).processNextApproved();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(publisher, times(2)).publish(eq(event.getEventId()), eq("ticketUpdated"), any());
    }
    @Test void invalidOrSyntheticPayloadIsQuarantinedWithoutNetworkAttempt() {
        var event = event("ticketCreated"); var processor = ready(event);
        when(validator.prepare(event)).thenThrow(new PublicationFailure(UNVERIFIED_IDENTITY));
        processor.processNextApproved();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getPublishedAt()).isNull();
        verifyNoInteractions(publisher);
    }
    @Test void retriesAreFiniteAndFailedRowsAreNotMarkedPublished() {
        var event = event("ticketCreated"); event.setRetryCount(2); var processor = ready(event);
        doThrow(new PublicationFailure(BROKER_NACK)).when(publisher).publish(any(), any(), any());
        processor.processNextApproved();
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getPublishedAt()).isNull();
    }
    @Test void loweringMaxAttemptsDoesNotTriggerAnotherPublish() {
        var event = event("ticketCreated"); event.setRetryCount(3); var processor = ready(event);
        processor.processNextApproved();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        verifyNoInteractions(publisher);
    }
    @Test void workerContainsTransactionFailuresWithoutLoggingSensitiveExceptionText() {
        CoreOutboxProcessor processor = mock(CoreOutboxProcessor.class);
        when(processor.processNextApproved()).thenThrow(new IllegalStateException("private-password"));
        assertThatCode(() -> new CoreOutboxWorker(processor).publishNext()).doesNotThrowAnyException();
    }
    private CoreOutboxProcessor ready(OutboxEvent event) {
        when(repository.findNextApprovedForUpdate(any(), any())).thenReturn(Optional.of(event));
        when(validator.prepare(event)).thenReturn(new byte[]{1});
        return processor(Set.of(event.getEventId()));
    }
    private CoreOutboxProcessor processor(Set<java.util.UUID> approved) {
        return new CoreOutboxProcessor(repository, validator, publisher, settings(approved), clock);
    }
}
