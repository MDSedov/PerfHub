package perfhub.model;

import java.time.Instant;
import java.util.UUID;

public record LoadTest(
        UUID id,
        Instant start,
        Instant end,
        LoadTestType type,
        String typeCode,
        String typeName,
        String comment,
        String reportStatus,
        String reportSummary) {

    public LoadTest(UUID id, Instant start, Instant end, LoadTestType type, String comment) {
        this(id, start, end, type, defaultTypeCode(type), defaultTypeName(type), comment, null, null);
    }

    public LoadTest(
            UUID id,
            Instant start,
            Instant end,
            LoadTestType type,
            String comment,
            String reportStatus,
            String reportSummary) {
        this(id, start, end, type, defaultTypeCode(type), defaultTypeName(type), comment, reportStatus, reportSummary);
    }

    public String effectiveTypeCode() {
        if (hasText(typeCode)) {
            return typeCode.trim();
        }
        return defaultTypeCode(type);
    }

    public String effectiveTypeName() {
        if (hasText(typeName)) {
            return typeName.trim();
        }
        return defaultTypeName(type);
    }

    private static String defaultTypeCode(LoadTestType type) {
        return type == null ? LoadTestType.UNKNOWN.value() : type.value();
    }

    private static String defaultTypeName(LoadTestType type) {
        if (type == null) {
            return "UNKNOWN";
        }
        return switch (type) {
            case MAX -> "Максимум";
            case STABILITY -> "Стабильность";
            case SOAK -> "Надежность";
            case PSU -> "PSU";
            case DT -> "DT";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
