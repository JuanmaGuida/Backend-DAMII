package com.reclamos.backend.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Texto que escribió la persona y, si ya eligió uno, el tipo de trámite elegido. */
public record AiClassifyRequest(
        @Size(max = 200) String summary,
        @NotBlank @Size(min = 10, max = 5000) String description,
        Long requestTypeId
) {
}
