package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateInformationRequest {
    @NotBlank
    private String messageForCitizen;
    private String internalMessage;
}