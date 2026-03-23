package com.example.chronicler.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Тип нагрузочного теста, определённый на основе анализа временного ряда интенсивности. */
public enum LoadTestType {
    MAX("max"),
    STABILITY("stability"),
    SOAK("soak"),
    PSU("psu"),
    DT("dt"),
    UNKNOWN("unknown");

    private final String value;

    LoadTestType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static LoadTestType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "max" -> MAX;
            case "stability", "confirm" -> STABILITY;
            case "soak" -> SOAK;
            case "psu" -> PSU;
            case "dt" -> DT;
            case "unknown" -> UNKNOWN;
            default -> throw new IllegalArgumentException("Unsupported load test type: " + value);
        };
    }

    public static LoadTestType fromDatabaseValue(String databaseValue) {
        if (databaseValue == null || databaseValue.isBlank()) {
            return UNKNOWN;
        }
        try {
            return fromValue(databaseValue);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
