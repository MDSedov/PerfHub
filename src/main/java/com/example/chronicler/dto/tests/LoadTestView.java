package com.example.chronicler.dto.tests;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Представление данных о нагрузочном тесте для ответов REST API. */
public record LoadTestView(
        String id,
        String start,
        String end,
        String type,
        long durationSeconds,
        RequirementComplianceView requirementCompliance,
        String comment) {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(MOSCOW_ZONE);

    public static LoadTestView fromModel(LoadTest loadTest, RequirementComplianceView compliance) {
        LoadTestType type = loadTest.type() == null ? LoadTestType.UNKNOWN : loadTest.type();
        return new LoadTestView(
                loadTest.id().toString(),
                formatInstant(loadTest.start()),
                formatInstant(loadTest.end()),
                type.value(),
                calculateDurationSeconds(loadTest.start(), loadTest.end()),
                compliance,
                loadTest.comment());
    }

    private static long calculateDurationSeconds(Instant start, Instant end) {
        if (start == null || end == null) {
            return 0L;
        }
        long duration = Duration.between(start, end).getSeconds();
        return duration < 0 ? 0 : duration;
    }

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return FORMATTER.format(instant);
    }
}
