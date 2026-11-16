package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.FormFieldType;
import java.util.List;

/** Historical display metadata only: never includes risk rules or proposal data. */
public record StaffTicketFormResponse(Long id, Integer version, List<Field> fields) {
    public record Field(String code, String label, FormFieldType type, List<?> options) {}
}
