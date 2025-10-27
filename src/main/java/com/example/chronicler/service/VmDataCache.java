package com.example.chronicler.service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class VmDataCache {

    private final AtomicReference<CachedVmData> cachedData = new AtomicReference<>();

    public void store(String type, Instant start, Instant end, String payload) {
        cachedData.set(new CachedVmData(type, start, end, payload));
    }

    public Optional<CachedVmData> getLatest() {
        return Optional.ofNullable(cachedData.get());
    }

    public void clear() {
        cachedData.set(null);
    }

    public record CachedVmData(String type, Instant start, Instant end, String payload) {}
}
