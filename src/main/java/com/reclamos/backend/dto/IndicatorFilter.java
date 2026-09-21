package com.reclamos.backend.dto;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.SlaStatus;
import com.reclamos.backend.entity.SlaType;
import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorFilter {
    private Long categoryId;
    private UUID neighborhoodId;
    private Priority priority;
    private String responsibleAreaId;
    private TicketStatus status;
    private SlaType slaType;
    private SlaStatus slaStatus;
}