package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.ResolutionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResolveTicketRequest {
    @NotNull
    private ResolutionType type;
    @NotBlank
    private String publicMessage;
    private String internalMessage;
}