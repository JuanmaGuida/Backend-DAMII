package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.CancellationReasonCode;
import jakarta.validation.constraints.*;

public record CreateCancellationRequest(@NotNull CancellationReasonCode reasonCode,
                                       @NotBlank @Size(max = 4000) String reason) {}
