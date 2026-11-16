package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateLabelRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    private String description;

    @NotNull
    private Boolean active;
}