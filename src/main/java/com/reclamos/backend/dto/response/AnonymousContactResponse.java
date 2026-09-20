package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.AnonymousContactChannel;

/**
 * Canal de contacto privado de un ticket anónimo. Su valor sólo se proyecta
 * dentro del detalle staff autorizado y se redacta de representaciones
 * textuales para evitar filtraciones accidentales en logs.
 */
public record AnonymousContactResponse(
        AnonymousContactChannel channel,
        String value
) {
    @Override
    public String toString() {
        return "AnonymousContactResponse[channel=<redacted>, value=<redacted>]";
    }
}
