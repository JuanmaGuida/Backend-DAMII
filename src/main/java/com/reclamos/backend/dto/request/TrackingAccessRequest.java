package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TrackingAccessRequest {
    @NotBlank(message = "El código de seguimiento es obligatorio")
    private String trackingCode;

    private String anonymousAccessPassword;

    @Override
    public String toString() {
        return "TrackingAccessRequest[trackingCode=<redacted>, anonymousAccessPassword=<redacted>]";
    }
}
