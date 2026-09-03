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
    private TicketStatus currentStatus;
    private String summary;
    private Instant createdAt;
    private Instant statusChangedAt;
    private RequestTypeSummary requestType;
    private CategorySummary category;
    private SubcategorySummary subcategory;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestTypeSummary {
        private Long id;
        private String code;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private Long id;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubcategorySummary {
        private Long id;
        private String name;
    }
}