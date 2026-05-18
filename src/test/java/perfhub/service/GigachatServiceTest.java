package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.GigachatClient;
import com.example.chronicler.dto.gigachat.GigachatMessage;
import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.registry.GigachatCompliancePromptProvider;
import com.example.chronicler.registry.GigachatPromptRegistry;
import com.example.chronicler.registry.GigachatTestsPromptProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GigachatServiceTest {

    @Mock
    private GigachatClient client;

    @Mock
    private GigachatPromptRegistry promptRegistry;

    @Mock
    private GigachatTestsPromptProvider testsPromptProvider;

    @Mock
    private GigachatCompliancePromptProvider compliancePromptProvider;

    @Mock
    private VmDataCache vmDataCache;

    @Mock
    private GigachatTestsCache gigachatTestsCache;

    private GigachatService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new GigachatService(
                client,
                promptRegistry,
                testsPromptProvider,
                compliancePromptProvider,
                vmDataCache,
                gigachatTestsCache);
        objectMapper = new ObjectMapper();
    }

    @Test
    void analyzeLoadTestsSendsSystemAndUserMessages() throws Exception {
        when(testsPromptProvider.loadPrompt()).thenReturn("system prompt");
        when(client.requestToken())
                .thenReturn(new GigachatTokenResponse("token", "Bearer", null, Duration.ofHours(1).toSeconds(), null));

        VmDataCache.CachedVmData cachedVmData = new VmDataCache.CachedVmData(
                List.of("latency"),
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T01:00:00Z"),
                "{\"data\":{\"value\":1}}");
        when(vmDataCache.getLatest()).thenReturn(Optional.of(cachedVmData));

        JsonNode expectedResponse = objectMapper.readTree("{\"result\":\"ok\"}");
        when(client.createCompletion(eq("token"), anyList())).thenReturn(expectedResponse);

        JsonNode actual = service.analyzeLoadTests(null);

        assertThat(actual).isSameAs(expectedResponse);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(client).createCompletion(eq("token"), captor.capture());

        @SuppressWarnings("unchecked")
        List<GigachatMessage> messages = (List<GigachatMessage>) captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("system prompt");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo(cachedVmData.payload());

        verify(gigachatTestsCache).store(expectedResponse);
    }
}
