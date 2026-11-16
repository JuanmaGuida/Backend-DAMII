package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.TicketType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    // DDA2-190 (QA FAIL): sólo se validaba obligatoriedad/longitud, así que un
    // valor fuera del namespace M1..M9 (p.ej. "area-1", "M0", "M10", "m6" o un
    // nombre humano) pasaba esta validación y recién fallaba en PostgreSQL por
    // ck_request_type_responsible_area_namespace, devolviendo un 409 genérico
    // de conflicto de persistencia en vez de un 400 de request inválido. Mismo
    // guardrail simétrico que affectedPopulationFactor más abajo: el patrón
    // espeja exactamente el CHECK de la migración V9
    // (responsible_area_id IN ('M1', 'M2', ..., 'M9')).
    @NotBlank(message = "responsibleAreaId es obligatorio")
    @Pattern(regexp = "^M[1-9]$", message = "responsibleAreaId debe pertenecer al namespace M1..M9")
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
