package com.reclamos.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record CreateTicketRequest(
        @NotNull Long requestTypeId,
        @NotBlank @Size(max = 200) String summary,
        @NotBlank String description,
        @NotNull Map<String, Object> formData,
        @Valid LocationData location
) {
    public record LocationData(
            @Size(max = 300) String addressLine,
            @Size(max = 150) String street,
            @Size(max = 30) String streetNumber,
            UUID neighborhoodId,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 500) String reference
    ) { }
}
