package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.notification.NotificationSender;
import com.reclamos.backend.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationProcessorTest {
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Test
    void successfulDeliveryMarksNotificationSent() {
        NotificationLog notification = pending();
        NotificationLogRepository repository = mock(NotificationLogRepository.class);
        NotificationSender sender = mock(NotificationSender.class);
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(notification));

        new NotificationProcessor(repository, sender, Clock.fixed(NOW, ZoneOffset.UTC))
                .processNextPending();

        assertEquals(NotificationStatus.SENT, notification.getStatus());
        assertEquals(1, notification.getAttemptCount());
        assertEquals(NOW, notification.getSentAt());
        assertNull(notification.getFailedAt());
        assertNull(notification.getFailureReason());
        verify(sender).send(any());
        verify(repository).save(notification);
    }

    @Test
    void failedDeliveryIsRecordedAndExceptionIsContained() {
        NotificationLog notification = pending();
        NotificationLogRepository repository = mock(NotificationLogRepository.class);
        NotificationSender sender = mock(NotificationSender.class);
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(notification));
        doThrow(new IllegalStateException("sensitive recipient data"))
                .when(sender).send(any());

        assertDoesNotThrow(() -> new NotificationProcessor(repository, sender,
                Clock.fixed(NOW, ZoneOffset.UTC)).processNextPending());

        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals(1, notification.getAttemptCount());
        assertEquals(NOW, notification.getFailedAt());
        assertNull(notification.getSentAt());
        assertEquals("Notification delivery failed (IllegalStateException)", notification.getFailureReason());
        assertFalse(notification.getFailureReason().contains("sensitive recipient data"));
        verify(repository).save(notification);
    }

    @Test
    void emptyQueueDoesNotCallSender() {
        NotificationLogRepository repository = mock(NotificationLogRepository.class);
        NotificationSender sender = mock(NotificationSender.class);
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.empty());

        assertFalse(new NotificationProcessor(repository, sender, Clock.fixed(NOW, ZoneOffset.UTC))
                .processNextPending());
        verifyNoInteractions(sender);
        verify(repository, never()).save(any());
    }

    private NotificationLog pending() {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        NotificationLog notification = new NotificationLog();
        notification.setId(42L);
        notification.setTicket(ticket);
        notification.setType(NotificationType.INFORMATION_REQUIRED);
        notification.setChannel("EMAIL");
        notification.setStatus(NotificationStatus.PENDING);
        return notification;
    }
}
