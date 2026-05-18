package com.example.chronicler.model;

public enum RequirementEvaluationStatus {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    ERROR;

    public static RequirementEvaluationStatus fromDatabaseValue(String value) {
        for (RequirementEvaluationStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown requirement evaluation status: " + value);
    }
}
