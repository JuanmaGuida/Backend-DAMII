package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.MessageVisibility;
import jakarta.validation.constraints.NotNull;

public record AttachmentUploadRequest(
        @NotNull(message = "La visibilidad es obligatoria") MessageVisibility visibility
) {
}
