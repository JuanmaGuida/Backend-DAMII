package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.MessageVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * POST /tickets/{id}/messages (Entidades V1.49 §10 "TicketMessage · mensajes
 * públicos e internos"). visibility la valida la autorización real en
 * TicketMessageService, no acá: quién puede mandar INTERNAL depende del rol
 * y del ownership del ticket, no es una regla de forma del DTO.
 */
@Data
public class TicketMessageRequest {

    @NotNull(message = "visibility es obligatorio")
    private MessageVisibility visibility;

    @NotBlank(message = "text es obligatorio")
    @Size(max = 4000, message = "text no puede superar los 4000 caracteres")
    private String text;
}
