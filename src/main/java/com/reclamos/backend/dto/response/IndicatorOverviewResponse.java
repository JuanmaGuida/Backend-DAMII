package com.reclamos.backend.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Aggregate-only data; attention is a separate, bounded operational list. */
public record IndicatorOverviewResponse(Instant generatedAt, Instant periodStart, Instant previousPeriodStart,
        Counts counts, List<Breakdown> statuses, List<Breakdown> categories,
        List<Breakdown> neighborhoods, List<Breakdown> areas, List<Trend> trend,
        List<AttentionTicket> attention) {
    public record Counts(long total, long active, long unassigned, long overdue, long nearDue, long urgent,
            long resolved, long escalated, long reopenCount, long completedSlaMeasured, long completedSlaMet,
            Double averageResolutionHours, long previousTotal) { }
    public record Breakdown(String key, String name, long count) { }
    public record Trend(Instant from, long created, long resolved) { }
    public record AttentionTicket(UUID id, String publicId, String summary, String currentStatus,
            String currentPriority, boolean escalated, boolean slaBreached, boolean slaNearDue,
            Instant resolutionDueAt, Instant updatedAt) { }
}
