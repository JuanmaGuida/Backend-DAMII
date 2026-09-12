package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TicketSlaService {
    private final TicketSlaRepository slaRepository;
    private final TicketRepository ticketRepository;
    private final SlaCalculationService calculationService;
    private final ResolutionSlaMilestoneService milestoneService;

    public Optional<TicketSla> startInitialResolutionCycle(Ticket ticket, Instant startedAt) {
        return calculationService.calculateResolutionSchedule(
                        startedAt, ticket.getCurrentPriority(), ticket.getTicketType())
                .map(schedule -> createCycle(ticket, 1, startedAt, schedule));
    }

    public Optional<TicketSla> recalculateInitialResolutionCycle(Ticket ticket, Instant effectiveNow) {
        Optional<SlaCalculationService.SlaSchedule> schedule = calculationService.calculateResolutionSchedule(
                ticket.getCreatedAt(), ticket.getCurrentPriority(), ticket.getTicketType());
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION);
        if (schedule.isEmpty()) {
            active.ifPresent(slaRepository::delete);
            ticket.setResolutionDueAt(null);
            ticketRepository.save(ticket);
            return Optional.empty();
        }

        if (active.isEmpty() && slaRepository.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION).orElse(0) > 0) {
            throw new TicketStateConflictException(
                    "El ciclo SLA inicial ya fue completado y no puede reclasificarse");
        }
        TicketSla sla = active.orElseGet(() -> newCycle(ticket, 1, ticket.getCreatedAt(), schedule.orElseThrow()));
        applySchedule(sla, ticket.getCreatedAt(), schedule.orElseThrow());
        sla.setCompletedAt(null);
        sla.setStatus(SlaStatus.RUNNING);
        sla.setPausedAt(null);
        sla.setTotalPausedSeconds(0);
        ticket.setResolutionDueAt(sla.getDueAt());
        ticketRepository.save(ticket);
        slaRepository.save(sla);
        milestoneService.processResolutionSlaMilestones(ticket, sla, effectiveNow);
        return Optional.of(sla);
    }

    public Optional<TicketSla> completeActiveResolutionCycle(Ticket ticket, Instant resolvedAt) {
        Optional<TicketSla> active = slaRepository.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        TicketSla sla = active.get();
        milestoneService.processResolutionSlaMilestones(ticket, sla, resolvedAt);
        if (sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE) {
            sla.setStatus(SlaStatus.MET);
        }
        sla.setCompletedAt(resolvedAt);
        return Optional.of(slaRepository.save(sla));
    }

    public Optional<TicketSla> startReopenedResolutionCycle(Ticket ticket, Instant reopenedAt) {
        Optional<SlaCalculationService.SlaSchedule> schedule = calculationService.calculateResolutionSchedule(
                reopenedAt, ticket.getCurrentPriority(), ticket.getTicketType());
        if (schedule.isEmpty()) {
            ticket.setResolutionDueAt(null);
            ticketRepository.save(ticket);
            return Optional.empty();
        }
        int nextCycle = slaRepository.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION).orElse(0) + 1;
        return Optional.of(createCycle(ticket, nextCycle, reopenedAt, schedule.orElseThrow()));
    }

    public Optional<TicketSla> findLatestResolutionCycle(Ticket ticket) {
        Ticket owner = ticket.getMainTicket() == null ? ticket : ticket.getMainTicket();
        return slaRepository.findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(owner.getId(), SlaType.RESOLUTION);
    }

    private TicketSla createCycle(Ticket ticket, int cycleNumber, Instant startedAt,
                                  SlaCalculationService.SlaSchedule schedule) {
        TicketSla sla = newCycle(ticket, cycleNumber, startedAt, schedule);
        ticket.setResolutionDueAt(sla.getDueAt());
        ticketRepository.save(ticket);
        return slaRepository.save(sla);
    }

    private TicketSla newCycle(Ticket ticket, int cycleNumber, Instant startedAt,
                               SlaCalculationService.SlaSchedule schedule) {
        TicketSla sla = new TicketSla();
        sla.setTicket(ticket);
        sla.setSlaType(SlaType.RESOLUTION);
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
}
