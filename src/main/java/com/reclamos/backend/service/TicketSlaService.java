package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.TicketSlaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketSlaService {
    private final TicketSlaRepository slaRepository;
    private final SlaCalculationService calculationService;
    private final TicketSlaMilestoneService milestoneService;

    public Optional<TicketSla> startFirstResponseCycle(Ticket ticket, Instant startedAt) {
        if (slaRepository.findMaxCycleNumber(ticket.getId(), SlaType.FIRST_RESPONSE).orElse(0) > 0) {
            return slaRepository.findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(
                    ticket.getId(), SlaType.FIRST_RESPONSE);
        }
        return calculationService.calculateSchedule(startedAt, ticket.getCurrentPriority(), SlaType.FIRST_RESPONSE)
                .map(schedule -> createCycle(ticket, SlaType.FIRST_RESPONSE, 1, startedAt, schedule));
    }

    public Optional<TicketSla> startInitialResolutionCycle(Ticket ticket, Instant startedAt) {
        return calculationService.calculateResolutionSchedule(
                        startedAt, ticket.getCurrentPriority(), ticket.getTicketType())
                .map(schedule -> createCycle(ticket, SlaType.RESOLUTION, 1, startedAt, schedule));
    }

    public Optional<TicketSla> recalculateInitialResolutionCycle(Ticket ticket, Instant effectiveNow) {
        Optional<SlaCalculationService.SlaSchedule> schedule = calculationService.calculateResolutionSchedule(
                ticket.getCreatedAt(), ticket.getCurrentPriority(), ticket.getTicketType());
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION);
        if (schedule.isEmpty()) {
            active.ifPresent(slaRepository::delete);
            return Optional.empty();
        }

        if (active.isEmpty() && slaRepository.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION).orElse(0) > 0) {
            throw new TicketStateConflictException(
                    "El ciclo SLA inicial ya fue completado y no puede reclasificarse");
        }
        TicketSla sla = active.orElseGet(() -> newCycle(
                ticket, SlaType.RESOLUTION, 1, ticket.getCreatedAt(), schedule.orElseThrow()));
        applySchedule(sla, ticket.getCreatedAt(), schedule.orElseThrow());
        sla.setCompletedAt(null);
        sla.setStatus(SlaStatus.RUNNING);
        sla.setPausedAt(null);
        sla.setTotalPausedSeconds(0);
        slaRepository.save(sla);
        milestoneService.processMilestones(ticket, sla, effectiveNow);
        return Optional.of(sla);
    }

    public Optional<TicketSla> completeFirstResponseCycle(Ticket ticket, Instant reviewedAt) {
        return completeActiveCycle(ticket, SlaType.FIRST_RESPONSE, reviewedAt);
    }

    public Optional<TicketSla> completeActiveResolutionCycle(Ticket ticket, Instant resolvedAt) {
        resumeActiveResolutionCycle(ticket, resolvedAt);
        return completeActiveCycle(ticket, SlaType.RESOLUTION, resolvedAt);
    }

    public Optional<TicketSla> pauseActiveResolutionCycle(Ticket ticket, Instant pausedAt) {
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION);
        if (active.isEmpty() || active.get().getPausedAt() != null) {
            return active;
        }
        TicketSla sla = active.get();
        milestoneService.processMilestones(ticket, sla, pausedAt);
        if (sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE) {
            sla.setPausedAt(pausedAt);
            slaRepository.save(sla);
        }
        return Optional.of(sla);
    }

    public Optional<TicketSla> resumeActiveResolutionCycle(Ticket ticket, Instant resumedAt) {
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION);
        if (active.isEmpty() || active.get().getPausedAt() == null) {
            return active;
        }

        TicketSla sla = active.get();
        Instant pausedAt = sla.getPausedAt();
        if (resumedAt.isBefore(pausedAt)) {
            throw new TicketStateConflictException("La reanudación SLA no puede ser anterior a la pausa");
        }
        SlaPolicy policy = sla.getPolicy();
        long effectivePausedSeconds = calculationService.effectiveSecondsBetween(pausedAt, resumedAt, policy);
        long remainingDueSeconds = calculationService.effectiveSecondsBetween(pausedAt, sla.getDueAt(), policy);
        if (sla.getStatus() == SlaStatus.RUNNING) {
            long remainingNearDueSeconds = calculationService.effectiveSecondsBetween(
                    pausedAt, sla.getNearDueAt(), policy);
            sla.setNearDueAt(calculationService.addEffectiveSeconds(resumedAt, remainingNearDueSeconds, policy));
        }
        sla.setDueAt(calculationService.addEffectiveSeconds(resumedAt, remainingDueSeconds, policy));
        sla.setTotalPausedSeconds(Math.addExact(sla.getTotalPausedSeconds(), effectivePausedSeconds));
        sla.setPausedAt(null);
        return Optional.of(slaRepository.save(sla));
    }

    public void terminateActiveCycles(Ticket ticket, Instant terminalAt) {
        terminateActiveCycle(ticket, SlaType.FIRST_RESPONSE, terminalAt);
        terminateActiveCycle(ticket, SlaType.RESOLUTION, terminalAt);
    }

    /** Punto de lifecycle preparado para el futuro caso de uso de DUPLICATE. */
    public Optional<TicketSla> stopActiveResolutionCycleForDuplicate(Ticket ticket, Instant duplicateAt) {
        return terminateActiveCycle(ticket, SlaType.RESOLUTION, duplicateAt);
    }

    public Optional<TicketSla> startReopenedResolutionCycle(Ticket ticket, Instant reopenedAt) {
        Optional<SlaCalculationService.SlaSchedule> schedule = calculationService.calculateResolutionSchedule(
                reopenedAt, ticket.getCurrentPriority(), ticket.getTicketType());
        if (schedule.isEmpty()) {
            return Optional.empty();
        }
        int nextCycle = slaRepository.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION).orElse(0) + 1;
        return Optional.of(createCycle(ticket, SlaType.RESOLUTION, nextCycle, reopenedAt, schedule.orElseThrow()));
    }

    public Optional<TicketSla> findLatestResolutionCycle(Ticket ticket) {
        Ticket owner = ticket.getMainTicket() == null ? ticket : ticket.getMainTicket();
        return slaRepository.findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(owner.getId(), SlaType.RESOLUTION);
    }

    /**
     * Proyección REST de vencimientos. TicketSla permanece como única fuente
     * persistente y los duplicados heredan los ciclos de su ticket principal.
     */
    public DeadlineSnapshot findDeadlineSnapshot(Ticket ticket) {
        Ticket owner = ticket.getMainTicket() == null ? ticket : ticket.getMainTicket();
        Instant firstResponseDueAt = slaRepository
                .findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(owner.getId(), SlaType.FIRST_RESPONSE)
                .map(TicketSla::getDueAt)
                .orElse(null);
        Instant resolutionDueAt = slaRepository
                .findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(owner.getId(), SlaType.RESOLUTION)
                .map(TicketSla::getDueAt)
                .orElse(null);
        return new DeadlineSnapshot(firstResponseDueAt, resolutionDueAt);
    }

    /** Carga una sola vez el último ciclo de cada owner para respuestas paginadas. */
    public Map<UUID, TicketSla> findLatestResolutionCycles(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> ownerByTicket = new HashMap<>();
        for (Ticket ticket : tickets) {
            Ticket owner = ticket.getMainTicket() == null ? ticket : ticket.getMainTicket();
            ownerByTicket.put(ticket.getId(), owner.getId());
        }
        Map<UUID, TicketSla> latestByOwner = new HashMap<>();
        slaRepository.findLatestByTicketIds(ownerByTicket.values(), SlaType.RESOLUTION)
                .forEach(sla -> latestByOwner.put(sla.getTicket().getId(), sla));

        Map<UUID, TicketSla> result = new HashMap<>();
        ownerByTicket.forEach((ticketId, ownerId) -> {
            TicketSla sla = latestByOwner.get(ownerId);
            if (sla != null) {
                result.put(ticketId, sla);
            }
        });
        return result;
    }

    private Optional<TicketSla> completeActiveCycle(Ticket ticket, SlaType type, Instant completedAt) {
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), type);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        TicketSla sla = active.get();
        milestoneService.processMilestones(ticket, sla, completedAt);
        if (sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE) {
            sla.setStatus(SlaStatus.MET);
        }
        sla.setPausedAt(null);
        sla.setCompletedAt(completedAt);
        return Optional.of(slaRepository.save(sla));
    }

    private Optional<TicketSla> terminateActiveCycle(Ticket ticket, SlaType type, Instant terminalAt) {
        if (type == SlaType.RESOLUTION) {
            resumeActiveResolutionCycle(ticket, terminalAt);
        }
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), type);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        TicketSla sla = active.get();
        milestoneService.processMilestones(ticket, sla, terminalAt);
        if (sla.getStatus() != SlaStatus.BREACHED) {
            sla.setStatus(SlaStatus.STOPPED);
        }
        sla.setPausedAt(null);
        sla.setCompletedAt(terminalAt);
        return Optional.of(slaRepository.save(sla));
    }

    private TicketSla createCycle(Ticket ticket, SlaType type, int cycleNumber, Instant startedAt,
                                  SlaCalculationService.SlaSchedule schedule) {
        TicketSla sla = newCycle(ticket, type, cycleNumber, startedAt, schedule);
        return slaRepository.save(sla);
    }

    private TicketSla newCycle(Ticket ticket, SlaType type, int cycleNumber, Instant startedAt,
                               SlaCalculationService.SlaSchedule schedule) {
        TicketSla sla = new TicketSla();
        sla.setTicket(ticket);
        sla.setSlaType(type);
        sla.setCycleNumber(cycleNumber);
        applySchedule(sla, startedAt, schedule);
        sla.setStatus(SlaStatus.RUNNING);
        sla.setTotalPausedSeconds(0);
        return sla;
    }

    private void applySchedule(TicketSla sla, Instant startedAt, SlaCalculationService.SlaSchedule schedule) {
        sla.setPolicy(schedule.policy());
        sla.setStartedAt(startedAt);
        sla.setNearDueAt(schedule.nearDueAt());
        sla.setDueAt(schedule.dueAt());
    }

    public record DeadlineSnapshot(Instant firstResponseDueAt, Instant resolutionDueAt) {
    }
}
