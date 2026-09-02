package com.reclamos.backend.exception;

public class InformationRequestExpiredException extends RuntimeException {
    public InformationRequestExpiredException() { super("El plazo para responder la solicitud venció"); }
}