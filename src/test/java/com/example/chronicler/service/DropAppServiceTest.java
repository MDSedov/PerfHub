package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.DropAppClient;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import java.util.List;
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

    @Test
    void getConfiguredNamespacesReturnsTrimmedDistinctValues() {
        when(configurationProvider.findValue("dropapp", "namespaces"))
                .thenReturn(Optional.of(" ns-a,ns-b , ns-a ,, "));

        assertThat(dropAppService.getConfiguredNamespaces()).containsExactly("ns-a", "ns-b");
    }

    @Test
    void resolveNamespacesUsesSelectedNamespacesWhenProvided() {
        assertThat(dropAppService.resolveNamespaces(List.of(" ns-b ", "ns-a", "ns-b", "")))
                .containsExactly("ns-b", "ns-a");
    }

    @Test
    void loadConfigurationUsesResolvedSelectedNamespaces() {
        when(dropAppClient.fetchConfiguration(List.of("ns-c")))
                .thenReturn(List.of(new DropAppPodConfiguration(
                        "ns-c", null, "pod-1", "container-1", "1.0.0", "2.12.1", "100m", "200m", "256Mi", "512Mi")));

        List<DropAppPodConfiguration> result = dropAppService.loadConfiguration(List.of("ns-c"));

        verify(dropAppClient).fetchConfiguration(List.of("ns-c"));
        assertThat(result).hasSize(1);
    }
}
