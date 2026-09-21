package com.reclamos.backend.notification;

import com.reclamos.backend.entity.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingNotificationSenderTest {
    @Test
    void logsTechnicalEvidenceWithoutRecipientOrMessageData(CapturedOutput output) {
        UUID ticketId = UUID.randomUUID();

        new LoggingNotificationSender().send(new NotificationDelivery(
                42L, ticketId, NotificationType.INFORMATION_REQUIRED, "EMAIL"));

        assertThat(output).contains("Notification simulated", "notificationId=42",
                        "ticketId=" + ticketId, "type=INFORMATION_REQUIRED", "status=SENT")
                .doesNotContain("trackingAccessCode", "trackingCodeHash", "anonymousContactValue",
                        "password", "internalMessage", "messageForCitizen");
    }
}