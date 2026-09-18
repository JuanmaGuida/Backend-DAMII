package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.TicketType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RequestTypeAdminRequest {
    @NotNull(message = "subcategoryId es obligatorio")
    private Long subcategoryId;

    @NotBlank(message = "El código es obligatorio")
    @Size(max = 100, message = "El código no puede superar los 100 caracteres")
    private String code;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 200, message = "El nombre no puede superar los 200 caracteres")
    private String name;

    @NotBlank(message = "La descripción es obligatoria")
    private String description;

    @NotNull(message = "ticketType es obligatorio")
    private TicketType ticketType;

    @NotBlank(message = "responsibleAreaId es obligatorio")
    @Size(max = 100, message = "responsibleAreaId no puede superar los 100 caracteres")
    private String responsibleAreaId;

    @NotNull(message = "minimumPriority es obligatorio")
    private Priority minimumPriority;

    @NotNull(message = "baseRisk es obligatorio")
    private Risk baseRisk;

    @NotNull(message = "affectedPopulationFactor es obligatorio")
    @DecimalMin(value = "0", inclusive = true, message = "affectedPopulationFactor no puede ser negativo")
    private BigDecimal affectedPopulationFactor;

    private boolean allowsAnonymous;

    private boolean requiresLocation;
}
