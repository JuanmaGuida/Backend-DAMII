package com.reclamos.backend.exception;

public class EvidenceRequiredException extends RuntimeException {
  public static final String CODE = "EVIDENCE_REQUIRED";

  public EvidenceRequiredException() {
    super("El nivel de riesgo detectado requiere adjuntar al menos una evidencia.");
  }
}