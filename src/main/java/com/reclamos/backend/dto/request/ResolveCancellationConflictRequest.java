package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.*;
import jakarta.validation.constraints.*;

public record ResolveCancellationConflictRequest(@NotNull CancellationRequestStatus decision,
        CancellationScope cancellationScope, @NotBlank @Size(max=4000) String decisionReason,
        @Size(max=4000) String publicMessage) {}
