package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.TicketType;
import jakarta.validation.constraints.DecimalMax;
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

    // QA (FAIL de "validaciones de datos y relaciones jerárquicas del
    // catálogo"): un valor > 1 pasaba esta validación y terminaba en 500 al
    // guardar, porque la base ya exige 0..1 vía
    // ck_request_type_affected_population_factor (Entidades V1.49 §7.3:
    // "Valor 0..1 que estima qué proporción de la población del barrio
    // podría verse afectada"). Faltaba el guardrail simétrico acá para que
    // se rechace con un 400 controlado en vez de dejar que lo frene la
    // constraint de base.
    @NotNull(message = "affectedPopulationFactor es obligatorio")
    @DecimalMin(value = "0", inclusive = true, message = "affectedPopulationFactor no puede ser negativo")
    @DecimalMax(value = "1", inclusive = true, message = "affectedPopulationFactor no puede ser mayor a 1")
    private BigDecimal affectedPopulationFactor;

    private boolean allowsAnonymous;

    private boolean requiresLocation;
}
