package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.SlaPolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Optional;
import java.util.Objects;

@Service
public class SlaCalculationService {
    private final SlaPolicyRepository policyRepository;
    private final BigDecimal nearDueThreshold;

    public SlaCalculationService(SlaPolicyRepository policyRepository) {
        this(policyRepository, new BigDecimal("0.80"));
    }

    @Autowired
    public SlaCalculationService(SlaPolicyRepository policyRepository,
                                 @Value("${ticket.sla.near-due-threshold:0.80}") BigDecimal nearDueThreshold) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "El repositorio de políticas es obligatorio");
        if (nearDueThreshold == null || nearDueThreshold.compareTo(BigDecimal.ZERO) <= 0
                || nearDueThreshold.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("El umbral de alerta SLA debe estar entre 0 y 1");
        }
        this.nearDueThreshold = nearDueThreshold;
    }

    public Optional<Instant> calculateDueAt(Instant start, Priority priority) {
        Objects.requireNonNull(start, "El instante inicial es obligatorio");
        Objects.requireNonNull(priority, "La prioridad es obligatoria");
        return calculateDueAt(start, priority, SlaType.RESOLUTION);
    }

    public Optional<Instant> calculateDueAt(Instant start, Priority priority, SlaType slaType) {
        Objects.requireNonNull(start, "El instante inicial es obligatorio");
        Objects.requireNonNull(priority, "La prioridad es obligatoria");
        Objects.requireNonNull(slaType, "El tipo de SLA es obligatorio");
        return policyRepository.findByPriorityAndSlaType(priority, slaType)
                .map(policy -> calculateDueAt(start, policy));
    }

    public Optional<Instant> calculateResolutionDueAt(Instant start, Priority priority, TicketType ticketType) {
        return calculateResolutionSchedule(start, priority, ticketType).map(SlaSchedule::dueAt);
    }

    public Optional<SlaSchedule> calculateResolutionSchedule(Instant start, Priority priority, TicketType ticketType) {
        Objects.requireNonNull(start, "El instante inicial es obligatorio");
        Objects.requireNonNull(priority, "La prioridad es obligatoria");
        Objects.requireNonNull(ticketType, "El tipo de ticket es obligatorio");
        if (ticketType == TicketType.INQUIRY || ticketType == TicketType.SUGGESTION) {
            return policyRepository.findByTicketTypeAndSlaType(ticketType, SlaType.RESOLUTION)
                    .map(policy -> calculateSchedule(start, policy));
        }
        return policyRepository.findByPriorityAndSlaType(priority, SlaType.RESOLUTION)
                .map(policy -> calculateSchedule(start, policy));
    }

    public Optional<Instant> calculateResolutionNearDueAt(Instant start, Priority priority, TicketType ticketType) {
        return calculateResolutionSchedule(start, priority, ticketType).map(SlaSchedule::nearDueAt);
    }

    public SlaSchedule calculateSchedule(Instant start, SlaPolicy policy) {
        Instant dueAt = calculateDueAt(start, policy);
        return new SlaSchedule(policy, calculateNearDueAt(start, policy, dueAt), dueAt);
    }

    public Instant calculateNearDueAt(Instant start, SlaPolicy policy) {
        Instant dueAt = calculateDueAt(start, policy);
        return calculateNearDueAt(start, policy, dueAt);
    }

    private Instant calculateNearDueAt(Instant start, SlaPolicy policy, Instant dueAt) {
        if (policy.getMode() == SlaMode.CONTINUOUS_24X7) {
            return start.plusSeconds(scaleSeconds(policy.getDurationSeconds()));
        }
        WorkCalendar calendar = policy.getWorkCalendar();
        return switch (policy.getDeadlineRule()) {
            case HOURS -> addBusinessTime(start, Duration.ofSeconds(scaleSeconds(policy.getDurationSeconds())), calendar);
            case BUSINESS_DAYS -> {
                ZoneId zone = ZoneId.of(calendar.getZoneId());
                Duration workday = Duration.between(
                        LocalDate.of(2000, 1, 3).atTime(calendar.getWorkdayStart()).atZone(zone).toInstant(),
                        LocalDate.of(2000, 1, 3).atTime(calendar.getWorkdayEnd()).atZone(zone).toInstant());
                long totalSeconds = Math.multiplyExact(workday.getSeconds(), policy.getDurationBusinessDays().longValue());
                yield addBusinessTime(start, Duration.ofSeconds(scaleSeconds(totalSeconds)), calendar);
            }
            case SAME_BUSINESS_DAY -> {
                ZoneId zone = ZoneId.of(calendar.getZoneId());
                Instant effectiveStart = nextWorkingInstant(start.atZone(zone), calendar, zone).toInstant();
                long effectiveSeconds = Duration.between(effectiveStart, dueAt).getSeconds();
                yield effectiveStart.plusSeconds(scaleSeconds(effectiveSeconds));
            }
        };
    }

    private long scaleSeconds(long seconds) {
        return BigDecimal.valueOf(seconds).multiply(nearDueThreshold)
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public Instant calculateDueAt(Instant start, SlaPolicy policy) {
        Objects.requireNonNull(start, "El instante inicial es obligatorio");
        Objects.requireNonNull(policy, "La política SLA es obligatoria");
        Objects.requireNonNull(policy.getMode(), "El modo de la política es obligatorio");
        Objects.requireNonNull(policy.getDeadlineRule(), "La regla de vencimiento es obligatoria");
        if (policy.getPriority() == Priority.CRITICAL && policy.getMode() != SlaMode.CONTINUOUS_24X7)
            throw new IllegalArgumentException("CRITICAL requiere el modo 24x7");
        if (policy.getPriority() != null && policy.getPriority() != Priority.CRITICAL
                && policy.getMode() != SlaMode.BUSINESS_HOURS)
            throw new IllegalArgumentException("Las prioridades no críticas requieren horario laboral");
        if (policy.getDeadlineRule() == SlaDeadlineRule.HOURS
                && (policy.getDurationSeconds() == null || policy.getDurationSeconds() <= 0))
            throw new IllegalArgumentException("La duración en horas debe ser positiva");
        if (policy.getMode() == SlaMode.CONTINUOUS_24X7) {
            return start.plusSeconds(policy.getDurationSeconds());
        }
        if (policy.getMode() != SlaMode.BUSINESS_HOURS || policy.getWorkCalendar() == null)
            throw new IllegalArgumentException("La política de horario laboral requiere un calendario");
        validateBusinessCalendar(policy.getWorkCalendar());
        return switch (policy.getDeadlineRule()) {
            case HOURS -> addBusinessTime(start, Duration.ofSeconds(policy.getDurationSeconds()), policy.getWorkCalendar());
            case BUSINESS_DAYS -> addBusinessDays(start, policy.getDurationBusinessDays(), policy.getWorkCalendar());
            case SAME_BUSINESS_DAY -> sameBusinessDay(start, policy.getWorkCalendar());
        };
    }

    private Instant addBusinessDays(Instant start, Integer days, WorkCalendar calendar) {
        if (days == null || days <= 0) throw new IllegalArgumentException("La cantidad de días hábiles debe ser positiva");
        ZoneId zone = ZoneId.of(calendar.getZoneId());
        ZonedDateTime cursor = nextWorkingInstant(start.atZone(zone), calendar, zone);
        for (int day = 0; day < days; day++) {
            LocalDate workDate = cursor.toLocalDate();
            Duration workday = Duration.between(
                    workDate.atTime(calendar.getWorkdayStart()).atZone(zone).toInstant(),
                    workDate.atTime(calendar.getWorkdayEnd()).atZone(zone).toInstant());
            cursor = addBusinessTime(cursor.toInstant(), workday, calendar).atZone(zone);
        }
        return cursor.toInstant();
    }

    private Instant sameBusinessDay(Instant start, WorkCalendar calendar) {
        ZoneId zone = ZoneId.of(calendar.getZoneId());
        ZonedDateTime workingInstant = nextWorkingInstant(start.atZone(zone), calendar, zone);
        return workingInstant.toLocalDate().atTime(calendar.getWorkdayEnd()).atZone(zone).toInstant();
    }

    private Instant addBusinessTime(Instant start, Duration remaining, WorkCalendar calendar) {
        ZoneId zone = ZoneId.of(calendar.getZoneId());
        ZonedDateTime cursor = nextWorkingInstant(start.atZone(zone), calendar, zone);
        while (!remaining.isZero()) {
            ZonedDateTime end = cursor.toLocalDate().atTime(calendar.getWorkdayEnd()).atZone(zone);
            Duration available = Duration.between(cursor.toInstant(), end.toInstant());
            if (remaining.compareTo(available) <= 0) return cursor.toInstant().plus(remaining);
            remaining = remaining.minus(available);
            cursor = nextWorkingInstant(end, calendar, zone);
        }
        return cursor.toInstant();
    }

    private void validateBusinessCalendar(WorkCalendar calendar) {
        ZoneId.of(calendar.getZoneId());
        if (calendar.getWorkdayStart() == null || calendar.getWorkdayEnd() == null
                || !calendar.getWorkdayStart().isBefore(calendar.getWorkdayEnd())
                || calendar.getWorkingDays() == null || calendar.getWorkingDays().isEmpty()
                || calendar.getNonWorkingDays() == null)
            throw new IllegalArgumentException("El calendario laboral no contiene una jornada válida");
    }

    private ZonedDateTime nextWorkingInstant(ZonedDateTime value, WorkCalendar calendar, ZoneId zone) {
        LocalDate date = value.toLocalDate();
        while (true) {
            if (calendar.getWorkingDays().contains(date.getDayOfWeek())
                    && !calendar.getNonWorkingDays().contains(date)) {
                ZonedDateTime opening = date.atTime(calendar.getWorkdayStart()).atZone(zone);
                ZonedDateTime closing = date.atTime(calendar.getWorkdayEnd()).atZone(zone);
                if (value.isBefore(opening)) return opening;
                if (value.isBefore(closing)) return value;
            }
            date = date.plusDays(1);
            value = date.atStartOfDay(zone);
        }
    }

    public record SlaSchedule(SlaPolicy policy, Instant nearDueAt, Instant dueAt) {
        public Long policyId() {
            return policy.getId();
        }
    }
}
