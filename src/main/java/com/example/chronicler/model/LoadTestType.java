package com.example.chronicler.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Тип нагрузочного теста, определённый на основе анализа временного ряда интенсивности. */
public enum LoadTestType {
    MAX("max"),
    CONFIRM("confirm"),
    SOAK("soak"),
    UNKNOWN("unknown");

    private final String value;

    LoadTestType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static LoadTestType fromDatabaseValue(String databaseValue) {
        if (databaseValue == null || databaseValue.isBlank()) {
            return UNKNOWN;
        }
        for (LoadTestType type : values()) {
            if (type.value.equalsIgnoreCase(databaseValue)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
