package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackingTicketResponse {
    private String publicId;
    private TicketStatus status;
    private String summary;
    private Instant createdAt;
    private Instant statusChangedAt;
    private RequestTypeSummary requestType;
    private CategorySummary category;
    private SubcategorySummary subcategory;
    private SlaSummary sla;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlaSummary {
        private Instant firstResponseDueAt;
        private Instant resolutionDueAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestTypeSummary {
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubcategorySummary {
        private String name;
    }
}