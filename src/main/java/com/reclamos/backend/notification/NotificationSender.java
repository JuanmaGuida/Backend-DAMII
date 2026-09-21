package com.reclamos.backend.notification;

public interface NotificationSender {
    void send(NotificationDelivery notification);
}