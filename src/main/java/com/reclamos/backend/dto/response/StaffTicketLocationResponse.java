package com.reclamos.backend.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record StaffTicketLocationResponse(String addressLine, String street, String streetNumber,
        UUID neighborhoodId, String neighborhoodName, BigDecimal latitude, BigDecimal longitude,
        String reference) {}
