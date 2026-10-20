package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AbandonReclassificationRequest(@NotBlank @Size(max = 4000) String reason) { }
