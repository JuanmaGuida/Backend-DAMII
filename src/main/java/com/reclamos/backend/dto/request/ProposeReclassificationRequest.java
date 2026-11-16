package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ProposeReclassificationRequest(
        @NotNull Long requestTypeId,
        Map<String, Object> formData,
        @Size(max = 4000) String messageForCitizen) { }
