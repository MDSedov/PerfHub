package com.example.chronicler.dto.tests;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Представление данных о нагрузочном тесте для ответов REST API. */
public record LoadTestView(String id, String start, String end, String type) {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(MOSCOW_ZONE);

    public static LoadTestView fromModel(LoadTest loadTest) {
        LoadTestType type = loadTest.type() == null ? LoadTestType.UNKNOWN : loadTest.type();
        return new LoadTestView(
                loadTest.id().toString(),
                FORMATTER.format(loadTest.start()),
                FORMATTER.format(loadTest.end()),
                type.value());
    }
}
