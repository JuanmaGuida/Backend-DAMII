package com.reclamos.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LabelSummaryResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
}