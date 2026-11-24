package com.reclamos.backend.dto.response;

/** Lo que este servidor permite hacer al personal; la UI lo usa para no ofrecer acciones que no van a funcionar. */
public record StaffCapabilitiesResponse(boolean simulatorEnabled) {
}
