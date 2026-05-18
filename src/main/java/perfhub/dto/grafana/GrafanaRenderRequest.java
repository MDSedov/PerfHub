package perfhub.dto.grafana;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrafanaRenderRequest(
        @NotNull(message = "testUuid is required") UUID testUuid,
        Boolean forceRefresh,
        Boolean cacheOnly) {

    public GrafanaRenderRequest(UUID testUuid) {
        this(testUuid, Boolean.FALSE, Boolean.FALSE);
    }

    public boolean shouldForceRefresh() {
        return Boolean.TRUE.equals(forceRefresh);
    }

    public boolean shouldUseCacheOnly() {
        return Boolean.TRUE.equals(cacheOnly);
    }
}
