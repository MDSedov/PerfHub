package com.example.chronicler.service;

import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class GrafanaRenderCache {

    private final AtomicReference<GrafanaRenderResponse> cachedResponse = new AtomicReference<>();

    public void store(GrafanaRenderResponse response) {
        cachedResponse.set(response);
    }

    public Optional<GrafanaRenderResponse> getLatest() {
        return Optional.ofNullable(cachedResponse.get());
    }

    public void clear() {
        cachedResponse.set(null);
    }
}
