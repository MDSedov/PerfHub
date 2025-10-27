package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.GigachatClient;
import com.example.chronicler.dto.gigachat.GigachatMessage;
import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.registry.GigachatPromptRegistry;
import com.example.chronicler.registry.GigachatTestsPromptProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class GigachatServiceTest {

    private GigachatClient client;
    private GigachatPromptRegistry promptRegistry;
    private GigachatTestsPromptProvider testsPromptProvider;
    private GigachatService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(GigachatClient.class);
        promptRegistry = Mockito.mock(GigachatPromptRegistry.class);
        testsPromptProvider = Mockito.mock(GigachatTestsPromptProvider.class);
        service = new GigachatService(client, promptRegistry, testsPromptProvider);
        objectMapper = new ObjectMapper();
    }

    @Test
    void analyzeLoadTestsSendsSystemAndUserMessages() throws Exception {
        when(testsPromptProvider.loadPrompt()).thenReturn("system prompt");
        when(client.requestToken())
                .thenReturn(new GigachatTokenResponse("token", "Bearer", null, Duration.ofHours(1).toSeconds(), null));

        JsonNode payload = objectMapper.readTree("{\"data\":{\"value\":1}}");
        JsonNode expectedResponse = objectMapper.readTree("{\"result\":\"ok\"}");
        when(client.createCompletion(eq("token"), anyList())).thenReturn(expectedResponse);

        JsonNode actual = service.analyzeLoadTests(payload);

        assertThat(actual).isSameAs(expectedResponse);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(client).createCompletion(eq("token"), captor.capture());

        @SuppressWarnings("unchecked")
        List<GigachatMessage> messages = (List<GigachatMessage>) captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("system prompt");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo(payload);
    }
}
