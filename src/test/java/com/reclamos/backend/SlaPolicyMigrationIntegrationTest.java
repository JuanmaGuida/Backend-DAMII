package com.reclamos.backend;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.SlaPolicyRepository;
import com.reclamos.backend.repository.WorkCalendarRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class SlaPolicyMigrationIntegrationTest {
    @Autowired private SlaPolicyRepository policies;
    @Autowired private WorkCalendarRepository calendars;

    @Test
    void migrationSeedsTheCompleteSlaPlanAndConfigurableCalendar() {
        WorkCalendar calendar = calendars.findAll().stream()
                .filter(value -> value.getName().equals("DEFAULT_BUSINESS_CALENDAR"))
                .findFirst().orElseThrow();
        assertEquals("America/Argentina/Buenos_Aires", calendar.getZoneId());
        assertEquals(EnumSet.range(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY),
                calendar.getWorkingDays());

        assertHours(Priority.CRITICAL, SlaType.FIRST_RESPONSE, 2, SlaMode.CONTINUOUS_24X7);
        assertHours(Priority.CRITICAL, SlaType.RESOLUTION, 24, SlaMode.CONTINUOUS_24X7);
        assertHours(Priority.HIGH, SlaType.FIRST_RESPONSE, 4, SlaMode.BUSINESS_HOURS);
        assertHours(Priority.HIGH, SlaType.RESOLUTION, 72, SlaMode.BUSINESS_HOURS);
        assertHours(Priority.MEDIUM, SlaType.FIRST_RESPONSE, 8, SlaMode.BUSINESS_HOURS);
        assertBusinessDays(Priority.MEDIUM, 5);
        assertHours(Priority.LOW, SlaType.FIRST_RESPONSE, 24, SlaMode.BUSINESS_HOURS);
        assertBusinessDays(Priority.LOW, 10);

        SlaPolicy inquiry = policies.findByTicketTypeAndSlaType(TicketType.INQUIRY, SlaType.RESOLUTION)
                .orElseThrow();
        assertEquals(SlaDeadlineRule.SAME_BUSINESS_DAY, inquiry.getDeadlineRule());
        assertBusinessDays(
                policies.findByTicketTypeAndSlaType(TicketType.SUGGESTION, SlaType.RESOLUTION).orElseThrow(), 15);
    }

    private void assertHours(Priority priority, SlaType type, long hours, SlaMode mode) {
        SlaPolicy policy = policies.findByPriorityAndSlaType(priority, type).orElseThrow();
        assertEquals(SlaDeadlineRule.HOURS, policy.getDeadlineRule());
        assertEquals(Duration.ofHours(hours).toSeconds(), policy.getDurationSeconds());
        assertEquals(mode, policy.getMode());
        assertEquals(mode == SlaMode.BUSINESS_HOURS, policy.getWorkCalendar() != null);
    }

    private void assertBusinessDays(Priority priority, int days) {
        assertBusinessDays(policies.findByPriorityAndSlaType(priority, SlaType.RESOLUTION).orElseThrow(), days);
    }

    private void assertBusinessDays(SlaPolicy policy, int days) {
        assertEquals(SlaDeadlineRule.BUSINESS_DAYS, policy.getDeadlineRule());
        assertEquals(days, policy.getDurationBusinessDays());
        assertEquals(SlaMode.BUSINESS_HOURS, policy.getMode());
        assertNotNull(policy.getWorkCalendar());
    }
}