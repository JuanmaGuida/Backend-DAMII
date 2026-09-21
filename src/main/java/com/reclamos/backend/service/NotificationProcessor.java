package com.reclamos.backend.service;

import com.reclamos.backend.entity.NotificationLog;
import com.reclamos.backend.entity.NotificationStatus;
import com.reclamos.backend.notification.NotificationDelivery;
import com.reclamos.backend.notification.NotificationQueued;
import com.reclamos.backend.notification.NotificationSender;
import com.reclamos.backend.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class NotificationProcessor {
    private final NotificationLogRepository notificationLogs;
    private final NotificationSender sender;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(NotificationQueued event) {
        NotificationLog notification = notificationLogs.findById(event.getNotificationId()).orElse(null);
        if (notification == null || notification.getStatus() != NotificationStatus.PENDING) {
            return;
        }
        notification.setAttemptCount(notification.getAttemptCount() + 1);
        try {
            sender.send(new NotificationDelivery(notification.getId(), notification.getTicket().getId(),
                    notification.getType(), notification.getChannel()));
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(clock.instant());
            notification.setFailedAt(null);
            notification.setFailureReason(null);
        } catch (RuntimeException failure) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailedAt(clock.instant());
            notification.setSentAt(null);
            notification.setFailureReason("Notification delivery failed (" + failure.getClass().getSimpleName() + ")");
        }
        notificationLogs.save(notification);
    }
}