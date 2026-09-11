package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketType;
import lombok.Data;

@Data
public class RequestTypeResponse {
    private Long id;
    private String code;
    private String name;
    private String description;
    private TicketType ticketType;
    private String responsibleAreaId;
    private boolean allowsAnonymous;
    private boolean requiresLocation;
}