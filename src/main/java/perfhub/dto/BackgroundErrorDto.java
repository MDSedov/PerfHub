package perfhub.dto;

import java.time.Instant;

public record BackgroundErrorDto(String message, Instant timestamp, String scope) {

    public BackgroundErrorDto(String message, Instant timestamp) {
        this(message, timestamp, "global");
    }
}
