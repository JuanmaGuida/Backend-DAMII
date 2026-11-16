package com.reclamos.backend.dto.response;

public record TrackingCodeResponse(String trackingCode) {
    @Override public String toString() { return "TrackingCodeResponse[trackingCode=<redacted>]"; }
}
