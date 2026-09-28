package com.reclamos.backend.notification;

import com.reclamos.backend.entity.NotificationType;
import lombok.Value;

import java.util.UUID;

@Value
public class NotificationDelivery {
    Long notificationId;
    UUID ticketId;
    String publicId;
    NotificationType type;
    String channel;

    public NotificationDelivery(Long notificationId, UUID ticketId, String publicId,
                                NotificationType type, String channel) {
        this.notificationId = notificationId;
        this.ticketId = ticketId;
        this.publicId = publicId;
        this.type = type;
        this.channel = channel;
    }

    public NotificationDelivery(Long notificationId, UUID ticketId, NotificationType type, String channel) {
        this(notificationId, ticketId, null, type, channel);
    }
}