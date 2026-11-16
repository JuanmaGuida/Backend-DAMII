package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SatisfactionSurveyRequest {
    @NotNull(message = "es obligatorio")
    @Min(value = 1, message = "debe ser mayor o igual a 1")
    @Max(value = 5, message = "debe ser menor o igual a 5")
    private Short score;

    private String comment;
}