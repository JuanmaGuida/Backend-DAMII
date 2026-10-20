package com.reclamos.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** NotificationLog es la cola durable: no se retienen tareas en memoria. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryJob {
    private static final int MAX_PER_SCAN = 50;
    private final NotificationProcessor processor;

    @Scheduled(fixedDelayString = "${notification.pending-scan-delay:1000}",
            initialDelayString = "${notification.pending-scan-delay:1000}")
    public void deliverPending() {
        // Cada llamada al proxy confirma y libera su conexión antes de tomar otra fila.
        // El límite evita monopolizar el scheduler cuando hay un backlog sostenido.
        for (int processed = 0; processed < MAX_PER_SCAN; processed++) {
            try {
                if (!processor.processNextPending()) return;
            } catch (RuntimeException failure) {
                // Un fallo transaccional deja PENDING por rollback. Se recupera en otro scan,
                // incluso después de reiniciar. No se reintentan filas FAILED del sender.
                log.error("Notification transaction failed; pending notifications will be scanned again ({})",
                        failure.getClass().getSimpleName());
                return;
            }
        }
    }
}
