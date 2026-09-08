package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Objects;

@Data @Entity @Table(name = "sla_policies") @NoArgsConstructor
public class SlaPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Priority priority;
    @Enumerated(EnumType.STRING) @Column(name = "ticket_type", length = 30) private TicketType ticketType;
    @Enumerated(EnumType.STRING) @Column(name = "sla_type", nullable = false, length = 30) private SlaType slaType;
    @Enumerated(EnumType.STRING) @Column(name = "deadline_rule", nullable = false, length = 30)
    private SlaDeadlineRule deadlineRule;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SlaMode mode;
    @Column(name = "duration_seconds") private Long durationSeconds;
    @Column(name = "duration_business_days") private Integer durationBusinessDays;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "work_calendar_id", foreignKey = @ForeignKey(name = "fk_sla_policy_work_calendar"))
    private WorkCalendar workCalendar;
    @PrePersist
    @PreUpdate
    public void validate() {
        Objects.requireNonNull(mode, "El modo SLA es obligatorio");
        Objects.requireNonNull(slaType, "El tipo de SLA es obligatorio");
        Objects.requireNonNull(deadlineRule, "La regla de vencimiento es obligatoria");
        if ((priority == null) == (ticketType == null))
            throw new IllegalArgumentException("La política debe seleccionar prioridad o tipo de ticket");
        if (ticketType != null && slaType != SlaType.RESOLUTION)
            throw new IllegalArgumentException("Los overrides por tipo sólo aplican a resolución");
        if (ticketType != null && ticketType != TicketType.INQUIRY && ticketType != TicketType.SUGGESTION)
            throw new IllegalArgumentException("Sólo INQUIRY y SUGGESTION tienen override de resolución");
        if (ticketType == TicketType.INQUIRY && deadlineRule != SlaDeadlineRule.SAME_BUSINESS_DAY)
            throw new IllegalArgumentException("INQUIRY requiere SAME_BUSINESS_DAY");
        if (ticketType == TicketType.SUGGESTION && deadlineRule != SlaDeadlineRule.BUSINESS_DAYS)
            throw new IllegalArgumentException("SUGGESTION requiere BUSINESS_DAYS");
        if (priority == Priority.CRITICAL && mode != SlaMode.CONTINUOUS_24X7)
            throw new IllegalArgumentException("CRITICAL requiere el modo 24x7");
        if (priority != null && priority != Priority.CRITICAL && mode != SlaMode.BUSINESS_HOURS)
            throw new IllegalArgumentException("Las prioridades no críticas requieren horario laboral");
        validateDuration();
        if (mode == SlaMode.BUSINESS_HOURS) {
            Objects.requireNonNull(workCalendar, "El SLA laboral requiere un calendario");
            workCalendar.validate();
            if (workCalendar.getWorkingDays().isEmpty())
                throw new IllegalArgumentException("El SLA laboral requiere al menos un día laboral");
        } else if (workCalendar != null) {
            throw new IllegalArgumentException("El SLA 24x7 no utiliza calendario laboral");
        }
    }

    private void validateDuration() {
        if (deadlineRule == SlaDeadlineRule.HOURS
                && (durationSeconds == null || durationSeconds <= 0 || durationBusinessDays != null))
            throw new IllegalArgumentException("HOURS requiere una duración positiva en segundos");
        if (deadlineRule == SlaDeadlineRule.BUSINESS_DAYS
                && (durationBusinessDays == null || durationBusinessDays <= 0 || durationSeconds != null))
            throw new IllegalArgumentException("BUSINESS_DAYS requiere una cantidad positiva de días");
        if (deadlineRule == SlaDeadlineRule.SAME_BUSINESS_DAY
                && (durationSeconds != null || durationBusinessDays != null))
            throw new IllegalArgumentException("SAME_BUSINESS_DAY no admite duración");
        if (deadlineRule != SlaDeadlineRule.HOURS && mode != SlaMode.BUSINESS_HOURS)
            throw new IllegalArgumentException("La regla seleccionada requiere horario laboral");
    }
}