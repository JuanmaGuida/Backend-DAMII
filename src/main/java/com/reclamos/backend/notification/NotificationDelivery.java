package com.reclamos.backend.notification;

import com.reclamos.backend.entity.NotificationType;
import lombok.Value;

import java.util.UUID;

@Value
public class NotificationDelivery {
    Long notificationId;
    UUID ticketId;
    NotificationType type;
    String channel;
}