package perfhub.service;

import perfhub.client.GigachatClient;
import perfhub.dto.gigachat.GigachatMessage;
import perfhub.dto.gigachat.GigachatRequest;
import perfhub.dto.gigachat.GigachatTokenResponse;
import perfhub.exception.GigachatPromptNotFoundException;
import perfhub.exception.VmDataNotCachedException;
import perfhub.registry.GigachatPromptRegistry;
import perfhub.registry.GigachatCompliancePromptProvider;
import perfhub.registry.GigachatTestsPromptProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class GigachatService {

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

    private final GigachatClient client;
    private final GigachatPromptRegistry promptRegistry;
    private final GigachatTestsPromptProvider testsPromptProvider;
    private final GigachatCompliancePromptProvider compliancePromptProvider;
    private final VmDataCache vmDataCache;
    private final GigachatTestsCache gigachatTestsCache;
    private volatile CachedToken cachedToken;

    public GigachatService(
            GigachatClient client,
            GigachatPromptRegistry promptRegistry,
            GigachatTestsPromptProvider testsPromptProvider,
            GigachatCompliancePromptProvider compliancePromptProvider,
            VmDataCache vmDataCache,
            GigachatTestsCache gigachatTestsCache) {
        this.client = client;
        this.promptRegistry = promptRegistry;
        this.testsPromptProvider = testsPromptProvider;
        this.compliancePromptProvider = compliancePromptProvider;
        this.vmDataCache = vmDataCache;
        this.gigachatTestsCache = gigachatTestsCache;
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
        return client.createCompletion(accessToken, List.of(new GigachatMessage("user", prompt)));
    }

    public JsonNode analyzeLoadTests(JsonNode payload) {
        String promptTemplate = testsPromptProvider.loadPrompt();
        String accessToken = resolveAccessToken();
        VmDataCache.CachedVmData cachedVmData = vmDataCache
                .getLatest()
                .orElseThrow(VmDataNotCachedException::new);
        JsonNode response = client.createCompletion(
                accessToken,
                List.of(
                        new GigachatMessage("system", promptTemplate),
                        new GigachatMessage("user", cachedVmData.payload())));
        gigachatTestsCache.store(response);
        return response;
    }

    public JsonNode analyzeTestCompliance() {
        String promptTemplate = compliancePromptProvider.loadPrompt();
        String accessToken = resolveAccessToken();
        VmDataCache.CachedVmData cachedVmData = vmDataCache
                .getLatest()
                .orElseThrow(VmDataNotCachedException::new);
        return client.createCompletion(
                accessToken,
                List.of(
                        new GigachatMessage("system", promptTemplate),
                        new GigachatMessage("user", cachedVmData.payload())));
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
