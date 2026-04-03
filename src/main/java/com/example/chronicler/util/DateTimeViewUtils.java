package com.example.chronicler.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Форматирование даты и времени для отображения на UI в московском часовом поясе. */
public final class DateTimeViewUtils {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(MOSCOW_ZONE);

    private DateTimeViewUtils() {}

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return FORMATTER.format(instant);
    }
}
