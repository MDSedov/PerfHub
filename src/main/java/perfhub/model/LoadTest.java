package perfhub.model;

import java.time.Instant;
import java.util.UUID;

public record LoadTest(
        UUID id,
        Instant start,
        Instant end,
        LoadTestType type,
        String comment,
        String reportStatus,
        String reportSummary) {

    public LoadTest(UUID id, Instant start, Instant end, LoadTestType type, String comment) {
        this(id, start, end, type, comment, null, null);
    }
}
