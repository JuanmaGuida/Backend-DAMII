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
    private PendingInformationRequestResponse pendingInformationRequest;

    public TrackingTicketResponse(String publicId, TicketStatus status, String summary, Instant createdAt,
                                  Instant statusChangedAt, RequestTypeSummary requestType,
                                  CategorySummary category, SubcategorySummary subcategory, SlaSummary sla) {
        this(publicId, status, summary, createdAt, statusChangedAt, requestType, category, subcategory, sla, null);
    }

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
