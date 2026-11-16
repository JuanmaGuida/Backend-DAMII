package com.reclamos.backend.repository;

import com.reclamos.backend.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    // El lock vive en la transacción del worker. Otros workers omiten la fila ocupada.
    @Query(value = "SELECT * FROM notification_logs WHERE status = 'PENDING' "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<NotificationLog> findNextPendingForUpdate();
}
