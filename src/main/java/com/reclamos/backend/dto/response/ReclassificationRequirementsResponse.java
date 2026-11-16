package com.reclamos.backend.dto.response;

import java.util.List;
import java.util.Map;

/** Citizen-safe structure, available only with ownership or accredited anonymous access. */
public record ReclassificationRequirementsResponse(
        List<FormFieldResponse> fields,
        List<String> missingFields,
        Map<String, Object> availableAnswers,
        boolean locationRequired,
        boolean neighborhoodRequired,
        boolean evidenceRequired,
        boolean identificationRequired) { }
