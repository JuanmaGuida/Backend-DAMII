package com.reclamos.backend.dto.response;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Extensión mínima del detalle interno. El contacto anónimo queda fuera del
 * contrato compartido por ciudadano/citizen-view y sólo lo completa el
 * service para AGENT o ADMIN.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public class StaffTicketDetailResponse extends TicketDetailResponse {
    private AnonymousContactResponse anonymousContact;

    @Override
    public String toString() {
        return "StaffTicketDetailResponse[detail=" + super.toString()
                + ", anonymousContact=<redacted>]";
    }
}
