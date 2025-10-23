package com.example.chronicler.service;

import com.example.chronicler.client.GigachatClient;
import com.example.chronicler.dto.gigachat.GigachatRequest;
import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.exception.GigachatPromptNotFoundException;
import com.example.chronicler.registry.GigachatPromptRegistry;
import com.example.chronicler.registry.GigachatTestsPromptProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class GigachatService {

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

    private final GigachatClient client;
    private final GigachatPromptRegistry promptRegistry;
    private final GigachatTestsPromptProvider testsPromptProvider;
    private final ObjectMapper objectMapper;

    private volatile CachedToken cachedToken;

    public GigachatService(
            GigachatClient client,
            GigachatPromptRegistry promptRegistry,
            GigachatTestsPromptProvider testsPromptProvider,
            ObjectMapper objectMapper) {
        this.client = client;
        this.promptRegistry = promptRegistry;
        this.testsPromptProvider = testsPromptProvider;
        this.objectMapper = objectMapper;
    }

    public GigachatTokenResponse fetchAccessToken() {
        GigachatTokenResponse response = client.requestToken();
        cacheToken(response);
        return response;
    }

    public JsonNode requestAnalysis(GigachatRequest request) {
        String promptTemplate = promptRegistry.findPrompt(request.promptKey())
                .orElseThrow(() -> new GigachatPromptNotFoundException(request.promptKey()));
        String prompt = applyVariables(promptTemplate, request.variables());
        String accessToken = resolveAccessToken();
        return client.createCompletion(accessToken, prompt);
    }

    public JsonNode analyzeLoadTests(JsonNode payload) {
        String promptTemplate = testsPromptProvider.loadPrompt();
        String payloadJson = serializePayload(payload);
        String prompt = promptTemplate.replace("{{payload}}", payloadJson);
        String accessToken = resolveAccessToken();
        return client.createCompletion(accessToken, prompt);
    }

    private String serializePayload(JsonNode payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize payload for GigaChat request", ex);
        }
    }

    private String resolveAccessToken() {
        CachedToken snapshot = cachedToken;
        if (snapshot != null && !snapshot.isExpired()) {
            return snapshot.token();
        }
        synchronized (this) {
            snapshot = cachedToken;
            if (snapshot == null || snapshot.isExpired()) {
                GigachatTokenResponse response = client.requestToken();
                cacheToken(response);
                snapshot = cachedToken;
            }
        }
        return Objects.requireNonNull(snapshot).token();
    }

    private void cacheToken(GigachatTokenResponse response) {
        Instant expiresAt = calculateExpiry(response);
        cachedToken = new CachedToken(response.accessToken(), expiresAt);
    }

    private Instant calculateExpiry(GigachatTokenResponse response) {
        Instant now = Instant.now();
        if (response.expiresIn() != null && response.expiresIn() > 0) {
            return now.plusSeconds(response.expiresIn());
        }
        if (response.expiresAt() != null && !response.expiresAt().isBlank()) {
            try {
                return OffsetDateTime.parse(response.expiresAt()).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall back to default below
            }
        }
        return now.plus(Duration.ofMinutes(30));
    }

    private String applyVariables(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private record CachedToken(String token, Instant expiresAt) {

        boolean isExpired() {
            Instant now = Instant.now();
            return now.plus(TOKEN_REFRESH_MARGIN).isAfter(expiresAt);
        }
    }
}
