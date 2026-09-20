package com.reclamos.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class SatisfactionSurveyResponse {
    private final Long id;
    private final UUID ticketId;
    private final Short score;
    private final String comment;
    private final Instant createdAt;
}