package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.DropAppClient;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropAppServiceTest {

    @Mock
    private DropAppClient dropAppClient;

    @Mock
    private ConfigurationProvider configurationProvider;

    private DropAppService dropAppService;

    @BeforeEach
    void setUp() {
        dropAppService = new DropAppService(dropAppClient, configurationProvider);
    }

    @Test
    void isConfiguredReturnsTrueWhenBaseUrlAndTokenArePresent() {
        when(configurationProvider.findValue("dropapp", "base-url")).thenReturn(Optional.of("https://dropapp.example"));
        when(configurationProvider.findValue("dropapp", "token")).thenReturn(Optional.of("secret-token"));

        assertThat(dropAppService.isConfigured()).isTrue();
    }

    @Test
    void isConfiguredReturnsFalseWhenTokenIsBlank() {
        when(configurationProvider.findValue("dropapp", "base-url")).thenReturn(Optional.of("https://dropapp.example"));
        when(configurationProvider.findValue("dropapp", "token")).thenReturn(Optional.of(" "));

        assertThat(dropAppService.isConfigured()).isFalse();
    }

    @Test
    void keepAliveRequestsNamespacesFromDropApp() {
        dropAppService.keepAlive();

        verify(dropAppClient).fetchNamespaces();
    }
}
