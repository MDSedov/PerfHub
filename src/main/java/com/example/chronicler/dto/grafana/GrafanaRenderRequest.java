package com.example.chronicler.dto.grafana;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrafanaRenderRequest(
        @NotNull(message = "testUuid is required") UUID testUuid,
        Boolean forceRefresh) {

    public GrafanaRenderRequest(UUID testUuid) {
        this(testUuid, Boolean.FALSE);
    }

    public boolean shouldForceRefresh() {
        return Boolean.TRUE.equals(forceRefresh);
    }
}
