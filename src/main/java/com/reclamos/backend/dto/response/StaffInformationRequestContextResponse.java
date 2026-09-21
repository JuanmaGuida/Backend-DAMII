package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.TicketStatus;

public record StaffInformationRequestContextResponse(
        String internalMessage,
        String requestedByModuleId,
        ActorType requestedByActorType,
        TicketStatus resumeStatus
) {
}
