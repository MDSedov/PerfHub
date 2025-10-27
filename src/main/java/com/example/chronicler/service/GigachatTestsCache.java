package com.example.chronicler.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class GigachatTestsCache {

    private final AtomicReference<String> cachedResponse = new AtomicReference<>();

    public void store(JsonNode response) {
        if (response == null) {
            cachedResponse.set(null);
        } else {
            cachedResponse.set(response.toString());
        }
    }

    public Optional<String> getLatest() {
        return Optional.ofNullable(cachedResponse.get());
    }

    public void clear() {
        cachedResponse.set(null);
    }
}
