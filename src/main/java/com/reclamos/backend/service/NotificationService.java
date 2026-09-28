package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.notification.NotificationQueued;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final String DEFAULT_IDENTIFIED_CHANNEL = "EMAIL";

    private final NotificationLogRepository notificationLogs;
    private final ModuleUserRepository moduleUsers;
    private final ApplicationEventPublisher events;

    public Optional<NotificationLog> queue(Ticket ticket, NotificationType type) {
        return queueCitizen(ticket, type);
    }

    public Optional<NotificationLog> queueCitizen(Ticket ticket, NotificationType type) {
        Optional<String> channel = resolveCitizenChannel(ticket);
        if (channel.isEmpty()) {
            return Optional.empty();
        }

        return persist(ticket, ticket.getCitizenId(), type, channel.get());
    }

    public Optional<NotificationLog> queueAssignedAgent(Ticket ticket, NotificationType type,
                                                        java.util.UUID actorCitizenId) {
        ModuleUser agent = ticket.getAssignedAgent();
        if (agent == null || !agent.isActive() || agent.getCitizenId().equals(actorCitizenId)) {
            return Optional.empty();
        }
        String channel = agent.getPreferredNotificationChannel();
        if (channel == null || channel.isBlank()) {
            channel = DEFAULT_IDENTIFIED_CHANNEL;
        }
        return persist(ticket, agent.getCitizenId(), type, channel);
    }

    private Optional<NotificationLog> persist(Ticket ticket, java.util.UUID recipientId,
                                              NotificationType type, String channel) {
        if (channel.isEmpty()) {
            return Optional.empty();
        }

        NotificationLog notification = new NotificationLog();
        notification.setTicket(ticket);
        notification.setCitizenId(recipientId);
        notification.setType(type);
        notification.setChannel(channel);
        notification.setStatus(NotificationStatus.PENDING);
        notification.setAttemptCount(0);
        notification = notificationLogs.save(notification);
        events.publishEvent(new NotificationQueued(notification.getId()));
        return Optional.of(notification);
    }

    private Optional<String> resolveCitizenChannel(Ticket ticket) {
        if (ticket.isAnonymous()) {
            return ticket.getAnonymousContactChannel() == AnonymousContactChannel.EMAIL
                    && ticket.getAnonymousContactValue() != null
                    ? Optional.of("EMAIL") : Optional.empty();
        }
        if (ticket.getPreferredNotificationChannel() != null
                && !ticket.getPreferredNotificationChannel().isBlank()) {
            return Optional.of(ticket.getPreferredNotificationChannel());
        }
        return moduleUsers.findByCitizenId(ticket.getCitizenId())
                .map(ModuleUser::getPreferredNotificationChannel)
                .filter(value -> !value.isBlank())
                .or(() -> Optional.of(DEFAULT_IDENTIFIED_CHANNEL));
    }
}