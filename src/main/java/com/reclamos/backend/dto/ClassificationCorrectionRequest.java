package com.reclamos.backend.dto;

import jakarta.validation.constraints.NotNull;

public record ClassificationCorrectionRequest(
        @NotNull(message = "requestTypeId es obligatorio") Long requestTypeId
) {
}
