package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.CancellationScope;
import jakarta.validation.constraints.*;

public record ReviewCancellationRequest(CancellationScope cancellationScope,
                                       @NotBlank @Size(max = 4000) String decisionReason,
                                       @Size(max = 4000) String publicMessage) {}
