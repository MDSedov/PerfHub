package com.example.chronicler.model;

public enum RequirementMetricType {
    LATENCY_P90,
    ERROR_RATE,
    CPU_UTILIZATION,
    MEMORY_UTILIZATION,
    POD_RESTARTS;

    public static RequirementMetricType fromDatabaseValue(String value) {
        for (RequirementMetricType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown requirement metric type: " + value);
    }
}
