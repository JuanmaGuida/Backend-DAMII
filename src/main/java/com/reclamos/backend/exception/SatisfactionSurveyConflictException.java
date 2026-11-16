package com.reclamos.backend.exception;

public class SatisfactionSurveyConflictException extends RuntimeException {
    public static final String CODE = "SATISFACTION_SURVEY_CONFLICT";

    public SatisfactionSurveyConflictException(String message) {
        super(message);
    }
}