package com.reclamos.backend.entity;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class SlaConfigurationValidationTest {
    @Test void rejectsInvalidZoneId() {
        WorkCalendar calendar = validCalendar();
        calendar.setZoneId("not/a-zone");
        assertThrows(DateTimeException.class, calendar::validate);
    }

    @Test void businessPolicyRequiresAtLeastOneWorkingDay() {
        WorkCalendar calendar = validCalendar();
        calendar.getWorkingDays().clear();
        SlaPolicy policy = policy(SlaMode.BUSINESS_HOURS, calendar);
        assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void continuousPolicyDoesNotAcceptCalendar() {
        SlaPolicy policy = policy(SlaMode.CONTINUOUS_24X7, validCalendar());
        policy.setPriority(Priority.CRITICAL);
        assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void nonCriticalPriorityCannotBeContinuous() {
        SlaPolicy policy = policy(SlaMode.CONTINUOUS_24X7, null);
        assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void criticalPolicyMustBeContinuous() {
        SlaPolicy policy = policy(SlaMode.BUSINESS_HOURS, validCalendar());
        policy.setPriority(Priority.CRITICAL);
        assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void criticalFirstResponseRequiresExactlyTwoHours() {
        SlaPolicy policy = policy(SlaMode.CONTINUOUS_24X7, null);
        policy.setPriority(Priority.CRITICAL);
        policy.setSlaType(SlaType.FIRST_RESPONSE);
        assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void criticalResolutionRequiresExactlyTwentyFourHours() {
        SlaPolicy policy = policy(SlaMode.CONTINUOUS_24X7, null);
        policy.setPriority(Priority.CRITICAL);
        policy.setDurationSeconds(Duration.ofHours(24).toSeconds());
        assertDoesNotThrow(policy::validate);
    }

    private SlaPolicy policy(SlaMode mode, WorkCalendar calendar) {
        SlaPolicy policy = new SlaPolicy();
        policy.setPriority(Priority.HIGH);
        policy.setMode(mode);
        policy.setSlaType(SlaType.RESOLUTION);
        policy.setDeadlineRule(SlaDeadlineRule.HOURS);
        policy.setDurationSeconds(3600L);
        policy.setWorkCalendar(calendar);
        return policy;
    }

    private WorkCalendar validCalendar() {
        WorkCalendar calendar = new WorkCalendar();
        calendar.setName("default");
        calendar.setZoneId("UTC");
        calendar.setWorkdayStart(LocalTime.of(9, 0));
        calendar.setWorkdayEnd(LocalTime.of(18, 0));
        calendar.setWorkingDays(EnumSet.of(DayOfWeek.MONDAY));
        return calendar;
    }
}