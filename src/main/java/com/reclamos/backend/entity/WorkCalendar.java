package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.*;
import java.util.*;

@Data @Entity @Table(name = "work_calendars") @NoArgsConstructor
public class WorkCalendar {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 100) private String name;
    @Column(name = "zone_id", nullable = false, length = 100) private String zoneId;
    @Column(name = "workday_start", nullable = false) private LocalTime workdayStart;
    @Column(name = "workday_end", nullable = false) private LocalTime workdayEnd;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_calendar_working_days", joinColumns = @JoinColumn(name = "work_calendar_id"))
    @Enumerated(EnumType.STRING) @Column(name = "day_of_week", nullable = false, length = 10)
    private Set<DayOfWeek> workingDays = new HashSet<>();
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_calendar_non_working_days", joinColumns = @JoinColumn(name = "work_calendar_id"))
    @Column(name = "non_working_date", nullable = false)
    private Set<LocalDate> nonWorkingDays = new HashSet<>();

    @PrePersist
    @PreUpdate
    public void validate() {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre del calendario es obligatorio");
        Objects.requireNonNull(zoneId, "La zona horaria es obligatoria");
        ZoneId.of(zoneId);
        Objects.requireNonNull(workdayStart, "El inicio de jornada es obligatorio");
        Objects.requireNonNull(workdayEnd, "El fin de jornada es obligatorio");
        if (!workdayStart.isBefore(workdayEnd)) {
            throw new IllegalArgumentException("El fin de jornada debe ser posterior al inicio");
        }
        Objects.requireNonNull(workingDays, "Los días laborales son obligatorios");
        Objects.requireNonNull(nonWorkingDays, "Los días no laborables son obligatorios");
        if (workingDays.contains(null) || nonWorkingDays.contains(null))
            throw new IllegalArgumentException("El calendario no admite días nulos");
    }
}