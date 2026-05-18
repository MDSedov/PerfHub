package com.example.chronicler.exception;

public class RequirementEvaluationException extends RuntimeException {

    public RequirementEvaluationException(String message) {
        super(message);
    }

    public RequirementEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
