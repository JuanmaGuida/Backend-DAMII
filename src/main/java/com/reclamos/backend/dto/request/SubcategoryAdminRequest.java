package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubcategoryAdminRequest {
    @NotNull(message = "categoryId es obligatorio")
    private Long categoryId;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar los 150 caracteres")
    private String name;

    // Subcategory.description es nullable en el schema (a diferencia de
    // Category/RequestType), así que no se exige @NotBlank acá.
    private String description;
}
