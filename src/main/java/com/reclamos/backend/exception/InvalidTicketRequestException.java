package com.reclamos.backend.exception;

public class InvalidTicketRequestException extends RuntimeException {
  public InvalidTicketRequestException(String message) {
    super(message);
  }
}