package com.reclamos.backend.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LoggingNotificationSender implements NotificationSender {
    @Override
    public void send(NotificationDelivery notification) {
        log.info("Notification simulated: notificationId={}, ticketId={}, type={}, channel={}, status=SENT",
                notification.getNotificationId(), notification.getTicketId(), notification.getType(),
                notification.getChannel());
    }
}