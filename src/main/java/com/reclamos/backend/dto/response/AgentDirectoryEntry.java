package com.reclamos.backend.dto.response;

import java.util.UUID;

/**
 * Fila del directorio de personal para el panel de administración.
 *
 * <p>Sale de {@code module_users} (el personal se da de alta localmente con su rol) y de los
 * tickets asignados a cada persona. No expone teléfono, citizenId ni identificadores internos.
 *
 * @param activeTickets     tickets principales asignados en un estado activo
 * @param resolvedThisMonth tickets asignados cuya última resolución cae en el mes en curso
 * @param slaMet            tickets asignados cuyo último ciclo de SLA de resolución cerrado se cumplió
 * @param slaBreached       tickets asignados cuyo último ciclo de SLA de resolución cerrado se venció
 */
public record AgentDirectoryEntry(
        UUID actorId,
        String firstName,
        String lastName,
        String email,
        String role,
        String areaId,
        boolean active,
        long activeTickets,
        long resolvedThisMonth,
        long slaMet,
        long slaBreached) {
}
