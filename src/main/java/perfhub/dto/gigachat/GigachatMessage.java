package perfhub.dto.gigachat;

import java.util.Objects;

public record GigachatMessage(String role, Object content) {

    public GigachatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
