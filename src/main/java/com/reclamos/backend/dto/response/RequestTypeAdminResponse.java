package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.TicketType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RequestTypeAdminResponse {
    private Long id;
    private Long subcategoryId;
    private String subcategoryName;
    private String code;
    private String name;
    private String description;
    private TicketType ticketType;
    private String responsibleAreaId;
    private Priority minimumPriority;
    private Risk baseRisk;
    private BigDecimal affectedPopulationFactor;
    private boolean allowsAnonymous;
    private boolean requiresLocation;
    private boolean active;
}
