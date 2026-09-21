package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PATCH /tickets/{id}/messages/{messageId}. A propósito no incluye
 * visibility: cambiarla después de creado el mensaje permitiría, por
 * ejemplo, convertir un INTERNAL en PUBLIC sin pasar por la autorización de
 * creación (TicketMessageService.requireWriteAuthority), filtrando
 * contenido que nunca debió ser visible para el ciudadano.
 */
@Data
public class TicketMessageUpdateRequest {

    @NotBlank(message = "text es obligatorio")
    @Size(max = 4000, message = "text no puede superar los 4000 caracteres")
    private String text;
}
