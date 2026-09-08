package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.SlaPolicyRepository;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlaCalculationServiceTest {
    private final SlaPolicyRepository policies = mock(SlaPolicyRepository.class);
    private final SlaCalculationService service = new SlaCalculationService(policies);
    private WorkCalendar calendar;

    @BeforeEach void setUp() {
        reset(policies);
        calendar = new WorkCalendar();
        calendar.setZoneId("America/Argentina/Buenos_Aires");
        calendar.setWorkdayStart(LocalTime.of(9, 0));
        calendar.setWorkdayEnd(LocalTime.of(18, 0));
        calendar.setWorkingDays(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        calendar.setNonWorkingDays(new HashSet<>());
    }

    @Test void calculatesInsideWorkday() { assertBusiness("2026-09-02T13:00:00Z", 2, "2026-09-02T15:00:00Z"); }
    @Test void startsBeforeWorkday() { assertBusiness("2026-09-02T10:00:00Z", 2, "2026-09-02T14:00:00Z"); }
    @Test void startsAfterWorkday() { assertBusiness("2026-09-02T22:00:00Z", 2, "2026-09-03T14:00:00Z"); }
    @Test void crossesToNextDay() { assertBusiness("2026-09-02T20:00:00Z", 2, "2026-09-03T13:00:00Z"); }
    @Test void crossesWeekend() { assertBusiness("2026-09-04T20:00:00Z", 2, "2026-09-07T13:00:00Z"); }
    @Test void skipsConfiguredHoliday() { calendar.getNonWorkingDays().add(LocalDate.of(2026, 9, 7)); assertBusiness("2026-09-04T20:00:00Z", 2, "2026-09-08T13:00:00Z"); }
    @Test void skipsSeveralHolidays() { calendar.getNonWorkingDays().addAll(Set.of(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8))); assertBusiness("2026-09-04T20:00:00Z", 2, "2026-09-09T13:00:00Z"); }
    @Test void startsExactlyAtOpening() { assertBusiness("2026-09-02T12:00:00Z", 2, "2026-09-02T14:00:00Z"); }
    @Test void startsExactlyAtClosing() { assertBusiness("2026-09-02T21:00:00Z", 2, "2026-09-03T14:00:00Z"); }
    @Test void canFinishExactlyAtClosing() { assertBusiness("2026-09-02T19:00:00Z", 2, "2026-09-02T21:00:00Z"); }
    @Test void usesAnExplicitlyConfiguredWorkingDay() {
        calendar.setWorkingDays(EnumSet.of(DayOfWeek.SATURDAY));
        assertBusiness("2026-09-04T20:00:00Z", 2, "2026-09-05T14:00:00Z");
    }
    @Test void skipsAWeekdayExcludedByConfiguration() {
        calendar.setWorkingDays(EnumSet.of(DayOfWeek.TUESDAY));
        assertBusiness("2026-09-07T13:00:00Z", 2, "2026-09-08T14:00:00Z");
    }
    @Test void startOnHolidayMovesToNextWorkday() {
        calendar.getNonWorkingDays().add(LocalDate.of(2026, 9, 2));
        assertBusiness("2026-09-02T13:00:00Z", 2, "2026-09-03T14:00:00Z");
    }
    @Test void durationCanCrossSeveralCompleteWorkdays() { assertBusiness("2026-09-07T12:00:00Z", 20, "2026-09-09T14:00:00Z"); }
    @Test void usesCalendarZoneRatherThanServerZone() {
        calendar.setZoneId("Europe/Madrid");
        assertBusiness("2026-09-02T06:00:00Z", 2, "2026-09-02T09:00:00Z");
    }
    @Test void criticalDuringWorkdayIsContinuous() { assertContinuous("2026-09-02T13:00:00Z", 4, "2026-09-02T17:00:00Z"); }
    @Test void criticalOutsideWorkdayIsContinuous() { assertContinuous("2026-09-02T23:00:00Z", 4, "2026-09-03T03:00:00Z"); }
    @Test void criticalCrossesWeekendWithoutPausing() { assertContinuous("2026-09-05T00:00:00Z", 48, "2026-09-07T00:00:00Z"); }
    @Test void criticalIgnoresConfiguredHoliday() { calendar.getNonWorkingDays().add(LocalDate.of(2026, 9, 2)); assertContinuous("2026-09-02T13:00:00Z", 4, "2026-09-02T17:00:00Z"); }
    @Test void nonCriticalPriorityCannotBeConfiguredAsContinuous() {
        SlaPolicy policy = new SlaPolicy(); policy.setPriority(Priority.HIGH); policy.setMode(SlaMode.CONTINUOUS_24X7);
        policy.setSlaType(SlaType.RESOLUTION); policy.setDeadlineRule(SlaDeadlineRule.HOURS);
        policy.setDurationSeconds(Duration.ofHours(2).toSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> service.calculateDueAt(Instant.parse("2026-09-06T00:00:00Z"), policy));
    }
    @Test void calculatesBusinessDaysWithoutAssumingFixedHoursInThePolicy() {
        SlaPolicy policy = businessDays(5);
        assertEquals(Instant.parse("2026-09-11T21:00:00Z"),
                service.calculateDueAt(Instant.parse("2026-09-07T12:00:00Z"), policy));
    }
    @Test void inquiryResolutionUsesTicketTypeOverride() {
        SlaPolicy override = sameBusinessDay();
        when(policies.findByTicketTypeAndSlaType(TicketType.INQUIRY, SlaType.RESOLUTION))
                .thenReturn(Optional.of(override));
        assertEquals(Instant.parse("2026-09-02T21:00:00Z"), service.calculateResolutionDueAt(
                Instant.parse("2026-09-02T13:00:00Z"), Priority.HIGH, TicketType.INQUIRY).orElseThrow());
        verify(policies, never()).findByPriorityAndSlaType(any(), any());
    }
    @Test void sameBusinessDayAtOpeningEndsAtSameClosing() {
        assertSameBusinessDay("2026-09-07T12:00:00Z", "2026-09-07T21:00:00Z");
    }
    @Test void sameBusinessDayBeforeOpeningEndsAtSameClosing() {
        assertSameBusinessDay("2026-09-07T10:00:00Z", "2026-09-07T21:00:00Z");
    }
    @Test void sameBusinessDayAtClosingEndsAtNextWorkdayClosing() {
        assertSameBusinessDay("2026-09-07T21:00:00Z", "2026-09-08T21:00:00Z");
    }
    @Test void sameBusinessDayAfterClosingEndsAtNextWorkdayClosing() {
        assertSameBusinessDay("2026-09-07T22:00:00Z", "2026-09-08T21:00:00Z");
    }
    @Test void sameBusinessDayOnSaturdayEndsAtNextConfiguredWorkdayClosing() {
        assertSameBusinessDay("2026-09-05T15:00:00Z", "2026-09-07T21:00:00Z");
    }
    @Test void sameBusinessDayOnSundayEndsAtNextConfiguredWorkdayClosing() {
        assertSameBusinessDay("2026-09-06T15:00:00Z", "2026-09-07T21:00:00Z");
    }
    @Test void sameBusinessDayOnHolidayEndsAtNextWorkdayClosing() {
        calendar.getNonWorkingDays().add(LocalDate.of(2026, 9, 7));
        assertSameBusinessDay("2026-09-07T15:00:00Z", "2026-09-08T21:00:00Z");
    }
    @Test void sameBusinessDaySkipsConsecutiveHolidays() {
        calendar.getNonWorkingDays().addAll(Set.of(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8)));
        assertSameBusinessDay("2026-09-07T15:00:00Z", "2026-09-09T21:00:00Z");
    }
    @Test void sameBusinessDayUsesConfiguredZone() {
        calendar.setZoneId("Europe/Madrid");
        assertSameBusinessDay("2026-09-07T06:00:00Z", "2026-09-07T16:00:00Z");
    }
    @Test void sameBusinessDayIsDeterministic() {
        SlaPolicy policy = sameBusinessDay();
        Instant start = Instant.parse("2026-09-07T15:00:00Z");
        assertEquals(service.calculateDueAt(start, policy), service.calculateDueAt(start, policy));
    }
    @Test void inquiryWithoutOverrideDoesNotFallBackToPriorityPolicy() {
        when(policies.findByTicketTypeAndSlaType(TicketType.INQUIRY, SlaType.RESOLUTION))
                .thenReturn(Optional.empty());
        when(policies.findByPriorityAndSlaType(Priority.HIGH, SlaType.RESOLUTION))
                .thenReturn(Optional.of(business(4)));

        assertTrue(service.calculateResolutionDueAt(Instant.parse("2026-09-02T13:00:00Z"),
                Priority.HIGH, TicketType.INQUIRY).isEmpty());
        verify(policies, never()).findByPriorityAndSlaType(any(), any());
    }
    @Test void suggestionResolutionUsesTicketTypeOverride() {
        SlaPolicy override = businessDays(15);
        override.setPriority(null); override.setTicketType(TicketType.SUGGESTION);
        when(policies.findByTicketTypeAndSlaType(TicketType.SUGGESTION, SlaType.RESOLUTION))
                .thenReturn(Optional.of(override));
        service.calculateResolutionDueAt(Instant.parse("2026-09-02T13:00:00Z"),
                Priority.LOW, TicketType.SUGGESTION).orElseThrow();
        verify(policies, never()).findByPriorityAndSlaType(any(), any());
    }
    @Test void suggestionWithoutOverrideDoesNotFallBackToPriorityPolicy() {
        when(policies.findByTicketTypeAndSlaType(TicketType.SUGGESTION, SlaType.RESOLUTION))
                .thenReturn(Optional.empty());
        when(policies.findByPriorityAndSlaType(Priority.LOW, SlaType.RESOLUTION))
                .thenReturn(Optional.of(businessDays(10)));

        assertTrue(service.calculateResolutionDueAt(Instant.parse("2026-09-02T13:00:00Z"),
                Priority.LOW, TicketType.SUGGESTION).isEmpty());
        verify(policies, never()).findByPriorityAndSlaType(any(), any());
    }
    @Test void complaintResolutionUsesPriorityPolicy() {
        assertResolutionUsesPriorityPolicy(TicketType.COMPLAINT);
    }
    @Test void requestResolutionUsesPriorityPolicy() {
        assertResolutionUsesPriorityPolicy(TicketType.REQUEST);
    }
    @Test void criticalFirstResponseCanUseTwoContinuousHours() {
        SlaPolicy policy = continuous(SlaType.FIRST_RESPONSE, 2);
        when(policies.findByPriorityAndSlaType(Priority.CRITICAL, SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.of(policy));
        assertEquals(Instant.parse("2026-09-06T02:00:00Z"), service.calculateDueAt(
                Instant.parse("2026-09-06T00:00:00Z"), Priority.CRITICAL, SlaType.FIRST_RESPONSE).orElseThrow());
    }
    @Test void highFirstResponseUsesConfiguredBusinessHours() {
        assertPriorityFirstResponse(Priority.HIGH, 4, "2026-09-07T16:00:00Z");
    }
    @Test void mediumFirstResponseUsesConfiguredBusinessHours() {
        assertPriorityFirstResponse(Priority.MEDIUM, 8, "2026-09-07T20:00:00Z");
    }
    @Test void criticalResolutionCanUseTwentyFourContinuousHours() {
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), service.calculateDueAt(
                Instant.parse("2026-09-06T00:00:00Z"), continuous(SlaType.RESOLUTION, 24)));
    }
    @Test void highResolutionSupportsSeventyTwoBusinessHours() {
        SlaPolicy policy = business(72);
        policy.setPriority(Priority.HIGH);
        policy.setSlaType(SlaType.RESOLUTION);
        assertEquals(Instant.parse("2026-09-16T21:00:00Z"),
                service.calculateDueAt(Instant.parse("2026-09-07T12:00:00Z"), policy));
    }
    @Test void lowFirstResponseSupportsTwentyFourBusinessHours() {
        SlaPolicy policy = business(24);
        policy.setPriority(Priority.LOW);
        policy.setSlaType(SlaType.FIRST_RESPONSE);
        assertEquals(Instant.parse("2026-09-09T18:00:00Z"),
                service.calculateDueAt(Instant.parse("2026-09-07T12:00:00Z"), policy));
    }
    @Test void sameInputsAreDeterministic() { SlaPolicy p = business(5); Instant start = Instant.parse("2026-09-04T20:00:00Z"); assertEquals(service.calculateDueAt(start, p), service.calculateDueAt(start, p)); }

    private void assertBusiness(String start, long hours, String expected) {
        assertEquals(Instant.parse(expected), service.calculateDueAt(Instant.parse(start), business(hours)));
    }
    private SlaPolicy business(long hours) {
        SlaPolicy policy = new SlaPolicy(); policy.setPriority(Priority.HIGH); policy.setMode(SlaMode.BUSINESS_HOURS);
        policy.setSlaType(SlaType.RESOLUTION); policy.setDeadlineRule(SlaDeadlineRule.HOURS);
        policy.setDurationSeconds(Duration.ofHours(hours).toSeconds()); policy.setWorkCalendar(calendar); return policy;
    }
    private SlaPolicy businessDays(int days) {
        SlaPolicy policy = new SlaPolicy(); policy.setPriority(Priority.MEDIUM); policy.setMode(SlaMode.BUSINESS_HOURS);
        policy.setSlaType(SlaType.RESOLUTION); policy.setDeadlineRule(SlaDeadlineRule.BUSINESS_DAYS);
        policy.setDurationBusinessDays(days); policy.setWorkCalendar(calendar); return policy;
    }
    private SlaPolicy sameBusinessDay() {
        SlaPolicy policy = new SlaPolicy(); policy.setTicketType(TicketType.INQUIRY);
        policy.setSlaType(SlaType.RESOLUTION); policy.setMode(SlaMode.BUSINESS_HOURS);
        policy.setDeadlineRule(SlaDeadlineRule.SAME_BUSINESS_DAY); policy.setWorkCalendar(calendar); return policy;
    }
    private void assertSameBusinessDay(String start, String expected) {
        assertEquals(Instant.parse(expected), service.calculateDueAt(Instant.parse(start), sameBusinessDay()));
    }
    private void assertContinuous(String start, long hours, String expected) {
        SlaPolicy policy = continuous(SlaType.RESOLUTION, hours);
        assertEquals(Instant.parse(expected), service.calculateDueAt(Instant.parse(start), policy));
    }
    private SlaPolicy continuous(SlaType type, long hours) {
        SlaPolicy policy = new SlaPolicy(); policy.setPriority(Priority.CRITICAL); policy.setMode(SlaMode.CONTINUOUS_24X7);
        policy.setSlaType(type); policy.setDeadlineRule(SlaDeadlineRule.HOURS);
        policy.setDurationSeconds(Duration.ofHours(hours).toSeconds()); return policy;
    }
    private void assertPriorityFirstResponse(Priority priority, long hours, String expected) {
        SlaPolicy policy = business(hours); policy.setPriority(priority); policy.setSlaType(SlaType.FIRST_RESPONSE);
        when(policies.findByPriorityAndSlaType(priority, SlaType.FIRST_RESPONSE)).thenReturn(Optional.of(policy));
        assertEquals(Instant.parse(expected), service.calculateDueAt(
                Instant.parse("2026-09-07T12:00:00Z"), priority, SlaType.FIRST_RESPONSE).orElseThrow());
    }
    private void assertResolutionUsesPriorityPolicy(TicketType ticketType) {
        SlaPolicy policy = business(4);
        when(policies.findByPriorityAndSlaType(Priority.HIGH, SlaType.RESOLUTION))
                .thenReturn(Optional.of(policy));
        assertEquals(Instant.parse("2026-09-02T17:00:00Z"), service.calculateResolutionDueAt(
                Instant.parse("2026-09-02T13:00:00Z"), Priority.HIGH, ticketType).orElseThrow());
        verify(policies, never()).findByTicketTypeAndSlaType(any(), any());
    }
}