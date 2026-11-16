package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryJobTest {
    @Test
    void stopsWhenNoPendingWorkRemains() {
        NotificationProcessor processor = mock(NotificationProcessor.class);
        when(processor.processNextPending()).thenReturn(true, true, false);

        new NotificationDeliveryJob(processor).deliverPending();

        verify(processor, times(3)).processNextPending();
    }

    @Test
    void boundsEachScanAndResumesTheBacklogOnTheNextScan() {
        NotificationProcessor processor = mock(NotificationProcessor.class);
        when(processor.processNextPending()).thenReturn(true);
        NotificationDeliveryJob job = new NotificationDeliveryJob(processor);

        job.deliverPending();
        verify(processor, times(50)).processNextPending();
        job.deliverPending();
        verify(processor, times(100)).processNextPending();
    }

    @Test
    void logsTransactionFailureAndTriesAgainOnALaterScan(CapturedOutput output) {
        NotificationProcessor processor = mock(NotificationProcessor.class);
        when(processor.processNextPending()).thenThrow(new IllegalStateException("sensitive payload"))
                .thenReturn(false);
        NotificationDeliveryJob job = new NotificationDeliveryJob(processor);

        job.deliverPending();
        job.deliverPending();

        verify(processor, times(2)).processNextPending();
        assertThat(output).contains("pending notifications will be scanned again", "IllegalStateException")
                .doesNotContain("sensitive payload");
    }
}
