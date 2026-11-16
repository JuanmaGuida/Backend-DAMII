package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.SlaStatus;
import com.reclamos.backend.entity.SlaType;
import lombok.Value;

@Value
public class SlaIndicatorResponse {
    SlaType slaType;
    SlaStatus status;
    long count;
}