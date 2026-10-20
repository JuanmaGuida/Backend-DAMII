package com.reclamos.backend.service;

import com.reclamos.backend.config.PriorityProperties;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriorityCalculationServiceTest {
    private final PriorityProperties rules = new PriorityProperties();
    private final SlaCalculationService calendar = new SlaCalculationService(mock(SlaPolicyRepository.class));
    private final PriorityCalculationService service = new PriorityCalculationService(rules, calendar);
    private static final Instant START = Instant.parse("2026-09-04T12:00:00Z"); // Friday 09:00 Argentina

    @ParameterizedTest @CsvSource({"0,LOW", "25,MEDIUM", "50,HIGH", "75,CRITICAL", "100,CRITICAL"})
    void baseRisksMapExactlyToApprovedThresholds(int risk, Priority expected) {
        assertThat(service.calculate(risk, 0, new PriorityCalculationService.AgeFraction(0,1), Priority.LOW))
                .isEqualTo(expected);
    }

    @ParameterizedTest @CsvSource({"0,0", "1,.25", "49,.25", "50,.5", "499,.5", "500,.75",
            "4999,.75", "5000,1", "2147483647,1"})
    void affectedBandBoundaries(int count, String expected) {
        assertThat(service.affectedScore(count)).isEqualByComparingTo(expected);
    }

    @Test void combinesAllFactorsAndAppliesMinimumAfterClassification() {
        assertThat(service.calculate(25, 5000, new PriorityCalculationService.AgeFraction(1,3), Priority.LOW))
                .isEqualTo(Priority.CRITICAL); // .15 + .25 + .05 = .45 exactly
        assertThat(service.calculate(0, 0, new PriorityCalculationService.AgeFraction(0,1), Priority.HIGH))
                .isEqualTo(Priority.HIGH);
        assertThat(service.calculate(25, 5000, new PriorityCalculationService.AgeFraction(999999999,3000000000L), Priority.LOW))
                .isEqualTo(Priority.HIGH); // infinitesimally below .45
    }

    @ParameterizedTest @CsvSource({".149999,LOW", ".15,MEDIUM", ".299999,MEDIUM", ".30,HIGH", ".449999,HIGH", ".45,CRITICAL"})
    void exactThresholds(String score, Priority expected) {
        rules.setWRisk(BigDecimal.ONE); rules.setWAffected(BigDecimal.ZERO); rules.setWAge(BigDecimal.ZERO);
        // Arbitrary configured thresholds around a known risk score preserve decimal precision.
        BigDecimal delta = new BigDecimal(".50").subtract(new BigDecimal(score));
        rules.setMediumThreshold(new BigDecimal(".15").add(delta));
        rules.setHighThreshold(new BigDecimal(".30").add(delta));
        rules.setCriticalThreshold(new BigDecimal(".45").add(delta));
        assertThat(service.calculate(50, 0, new PriorityCalculationService.AgeFraction(0,1), Priority.LOW)).isEqualTo(expected);
    }

    @Test void validatesConfigurationIncludingNestedBands() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(rules)).isEmpty();
            rules.setWRisk(new BigDecimal("1.1")); assertThat(validator.validate(rules)).isNotEmpty();
            rules.setWRisk(null); assertThat(validator.validate(rules)).isNotEmpty();
            rules.setWRisk(new BigDecimal(".60")); rules.setHighThreshold(new BigDecimal(".1"));
            assertThat(validator.validate(rules)).isNotEmpty();
            rules.setHighThreshold(new BigDecimal(".30")); rules.setScanDelay(0); rules.setBatchSize(1001);
            assertThat(validator.validate(rules)).hasSize(2);
            rules.setScanDelay(1); rules.setBatchSize(1);
            rules.setAffectedBands(List.of(new PriorityProperties.Band(1,"0")));
            assertThat(validator.validate(rules)).isNotEmpty();
            rules.setAffectedBands(List.of(new PriorityProperties.Band(0,"0"), new PriorityProperties.Band(0,"2")));
            assertThat(validator.validate(rules)).isNotEmpty();
            rules.setAffectedBands(null); assertThat(validator.validate(rules)).isNotEmpty();
        }
    }

    @Test void rejectsInvalidInputs() {
        assertThatThrownBy(() -> service.affectedScore(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculate(101,0,new PriorityCalculationService.AgeFraction(0,1),Priority.LOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriorityCalculationService.AgeFraction(2,1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void continuousAgeAtEightyPercentExpiryAndAfterExpiry() {
        TicketSla sla = cycle(continuous(), START, 100);
        assertAge(sla, START.plusSeconds(80), ".8");
        assertAge(sla, START.plusSeconds(100), "1");
        assertAge(sla, START.plusSeconds(10000), "1");
        assertAge(sla, START.minusSeconds(1), "0");
    }

    @Test void businessAgeSkipsWeekendsHolidayAndClosedHours() {
        SlaPolicy policy = business();
        policy.getWorkCalendar().getNonWorkingDays().add(LocalDate.of(2026,9,7));
        TicketSla sla = cycle(policy, START, 20*3600);
        assertAge(sla, Instant.parse("2026-09-05T12:00:00Z"), ".4");
        assertAge(sla, Instant.parse("2026-09-07T20:00:00Z"), ".4");
        assertAge(sla, Instant.parse("2026-09-08T20:00:00Z"), ".8");
        assertAge(sla, sla.getDueAt(), "1");
    }

    @Test void noApplicableCycleAndCompletedHistoricalBreachHaveZeroAge() {
        assertAge(null, START, "0");
        TicketSla sla=cycle(continuous(),START,100);
        sla.setCompletedAt(START.plusSeconds(100)); sla.setStatus(SlaStatus.BREACHED);
        assertAge(sla,START.plusSeconds(200),"0");
        sla.setCompletedAt(null); sla.setSlaType(SlaType.FIRST_RESPONSE); assertAge(sla,START,"0");
    }

    @Test void multipleActualPauseResumeOperationsPreserveOriginalDenominator() {
        TicketSla sla=cycle(continuous(),START,100);
        var repository=mock(TicketSlaRepository.class);
        when(repository.findActiveForUpdate(any(),eq(SlaType.RESOLUTION))).thenReturn(Optional.of(sla));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var lifecycle=new TicketSlaService(repository,calendar,mock(TicketSlaMilestoneService.class));
        Ticket ticket=new Ticket(); ticket.setId(UUID.randomUUID());
        lifecycle.pauseActiveResolutionCycle(ticket,START.plusSeconds(20));
        assertAge(sla,START.plusSeconds(50),".2");
        lifecycle.resumeActiveResolutionCycle(ticket,START.plusSeconds(50));
        assertThat(sla.getTotalPausedSeconds()).isEqualTo(30);
        assertAge(sla,START.plusSeconds(70),".4");
        lifecycle.pauseActiveResolutionCycle(ticket,START.plusSeconds(70));
        assertAge(sla,START.plusSeconds(100),".4");
        lifecycle.resumeActiveResolutionCycle(ticket,START.plusSeconds(100));
        assertThat(sla.getTotalPausedSeconds()).isEqualTo(60);
        assertThat(sla.getDueAt()).isEqualTo(START.plusSeconds(160));
        assertAge(sla,START.plusSeconds(140),".8");
        assertAge(sla,START.plusSeconds(160),"1");
        TicketSla reopened=cycle(continuous(),START.plusSeconds(200),100);
        reopened.setCycleNumber(2); assertAge(reopened,START.plusSeconds(210),".1");
    }

    @Test void businessPauseAcrossWeekendAndHolidayCountsOnlyEffectiveSeconds() {
        var policy=business(); policy.getWorkCalendar().getNonWorkingDays().add(LocalDate.of(2026,9,7));
        TicketSla sla=cycle(policy,START,16*3600);
        var repo=mock(TicketSlaRepository.class);
        when(repo.findActiveForUpdate(any(),eq(SlaType.RESOLUTION))).thenReturn(Optional.of(sla));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var lifecycle=new TicketSlaService(repo,calendar,mock(TicketSlaMilestoneService.class));
        Ticket t=new Ticket(); t.setId(UUID.randomUUID());
        lifecycle.pauseActiveResolutionCycle(t,START.plusSeconds(4*3600));
        assertAge(sla,Instant.parse("2026-09-08T16:00:00Z"),".25");
        lifecycle.resumeActiveResolutionCycle(t,Instant.parse("2026-09-08T16:00:00Z"));
        assertThat(sla.getTotalPausedSeconds()).isEqualTo(8*3600);
        assertAge(sla,Instant.parse("2026-09-08T20:00:00Z"),".5");
        sla.setStatus(SlaStatus.NEAR_DUE);
        lifecycle.pauseActiveResolutionCycle(t,Instant.parse("2026-09-09T16:00:00Z"));
        Instant near=sla.getNearDueAt();
        lifecycle.resumeActiveResolutionCycle(t,Instant.parse("2026-09-09T17:00:00Z"));
        assertThat(sla.getNearDueAt()).isEqualTo(near);
        assertAge(sla,Instant.parse("2026-09-09T17:00:00Z"),".75");
    }

    @Test void fractionalPauseDoesNotChangeOriginalDuration() {
        TicketSla sla=cycle(continuous(),START,100);
        var repo=mock(TicketSlaRepository.class);
        when(repo.findActiveForUpdate(any(),eq(SlaType.RESOLUTION))).thenReturn(Optional.of(sla));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var lifecycle=new TicketSlaService(repo,calendar,mock(TicketSlaMilestoneService.class));
        Ticket t=new Ticket(); t.setId(UUID.randomUUID());
        lifecycle.pauseActiveResolutionCycle(t,START.plusMillis(20500));
        assertAge(sla,START.plusMillis(21000),".2");
        lifecycle.resumeActiveResolutionCycle(t,START.plusMillis(21700));
        assertThat(service.age(sla,START.plusMillis(21700)).duration()).isEqualTo(100);
        assertAge(sla,START.plusMillis(21700),".2");
        assertAge(sla,sla.getDueAt(),"1");
    }

    @Test void sameBusinessDayOriginalDurationUsesOnlyRemainingWorkday() {
        var policy=business(); policy.setDeadlineRule(SlaDeadlineRule.SAME_BUSINESS_DAY);
        TicketSla sla=new TicketSla(); sla.setPolicy(policy); sla.setSlaType(SlaType.RESOLUTION);
        sla.setStartedAt(START.plusSeconds(4*3600));
        sla.setDueAt(calendar.calculateDueAt(sla.getStartedAt(),policy));
        assertAge(sla,START.plusSeconds(6*3600),".5");
    }

    private void assertAge(TicketSla sla, Instant now, String score) {
        assertThat(service.age(sla,now).score()).isEqualByComparingTo(score);
    }
    private TicketSla cycle(SlaPolicy policy,Instant start,long seconds) {
        policy.setDeadlineRule(SlaDeadlineRule.HOURS); policy.setDurationSeconds(seconds);
        TicketSla sla=new TicketSla(); sla.setStartedAt(start); sla.setPolicy(policy);
        sla.setSlaType(SlaType.RESOLUTION); sla.setStatus(SlaStatus.RUNNING);
        sla.setNearDueAt(calendar.addEffectiveSeconds(start,seconds*8/10,policy));
        sla.setDueAt(calendar.addEffectiveSeconds(start,seconds,policy)); return sla;
    }
    private SlaPolicy continuous() { var p=new SlaPolicy(); p.setMode(SlaMode.CONTINUOUS_24X7); return p; }
    private SlaPolicy business() {
        var c=new WorkCalendar(); c.setZoneId("America/Argentina/Buenos_Aires");
        c.setWorkdayStart(LocalTime.of(9,0)); c.setWorkdayEnd(LocalTime.of(17,0));
        c.setWorkingDays(EnumSet.range(DayOfWeek.MONDAY,DayOfWeek.FRIDAY)); c.setNonWorkingDays(new HashSet<>());
        var p=new SlaPolicy(); p.setMode(SlaMode.BUSINESS_HOURS); p.setWorkCalendar(c); return p;
    }
}
