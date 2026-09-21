package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackingTicketResponse {
    private String publicId;
    private TicketStatus status;
    private String summary;
    private String description;
    private Instant createdAt;
    private Instant statusChangedAt;
    private RequestTypeSummary requestType;
    private CategorySummary category;
    private SubcategorySummary subcategory;
    private SlaSummary sla;
    private List<TicketAttachmentResponse> attachments;
    private PendingInformationRequestResponse pendingInformationRequest;

    /**
     * Overload histórico sin description/attachments/pendingInformationRequest,
     * preservado porque varios tests de seguridad/routing (no relacionados con
     * el contenido de estos campos) todavía lo instancian directamente.
     */
    public TrackingTicketResponse(String publicId, TicketStatus status, String summary, Instant createdAt,
                                  Instant statusChangedAt, RequestTypeSummary requestType,
                                  CategorySummary category, SubcategorySummary subcategory, SlaSummary sla) {
        this(publicId, status, summary, null, createdAt, statusChangedAt, requestType, category, subcategory, sla,
                null, null);
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
