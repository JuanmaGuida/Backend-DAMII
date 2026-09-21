package com.reclamos.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LabelResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private boolean active;
    private long ticketCount;
}