package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.notification.NotificationQueued;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    private final NotificationLogRepository repository = mock(NotificationLogRepository.class);
    private final ModuleUserRepository users = mock(ModuleUserRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationService service = new NotificationService(repository, users, events);

    @Test
    void queuesPendingNotificationAndInternalEventForIdentifiedTicket() {
        Ticket ticket = ticket(false);
        ticket.setPreferredNotificationChannel("EMAIL");
        when(repository.save(any())).thenAnswer(invocation -> {
            NotificationLog value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });

        NotificationLog result = service.queue(ticket, NotificationType.TICKET_REGISTERED).orElseThrow();

        assertEquals(NotificationStatus.PENDING, result.getStatus());
        assertEquals(0, result.getAttemptCount());
        assertEquals(ticket.getCitizenId(), result.getCitizenId());
        assertEquals("EMAIL", result.getChannel());
        verify(events).publishEvent(new NotificationQueued(7L));
    }

    @Test
    void anonymousTicketWithoutSupportedExplicitContactDoesNotInventRecipient() {
        Ticket ticket = ticket(true);
        ticket.setAnonymousContactChannel(AnonymousContactChannel.PHONE);
        ticket.setAnonymousContactValue("555-secret");

        assertTrue(service.queue(ticket, NotificationType.TICKET_REGISTERED).isEmpty());
        verifyNoInteractions(repository, users, events);
    }

    private Ticket ticket(boolean anonymous) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setAnonymous(anonymous);
        ticket.setCitizenId(anonymous ? null : UUID.randomUUID());
        return ticket;
    }
}