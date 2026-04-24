package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.DropAppClient;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
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
    void getConfiguredNamespacesReturnsTrimmedDistinctValues() {
        when(configurationProvider.findValue("dropapp", "namespaces"))
                .thenReturn(Optional.of(" ns-a,ns-b ns-c\nns-a ,, "));

        assertThat(dropAppService.getConfiguredNamespaces()).containsExactly("ns-a", "ns-b", "ns-c");
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

    @Test
    void loadConfigurationWithComponentsDelegatesToClient() {
        List<DropAppConfigurationComponent> components = List.of(
                DropAppConfigurationComponent.PODS,
                DropAppConfigurationComponent.CONFIGMAPS);
        when(dropAppClient.fetchConfiguration(List.of("ns-x"), components))
                .thenReturn(List.of(new DropAppPodConfiguration(
                        "ns-x", "ConfigMap", "cm-1", "Ключей: 3", "", "2.13.0", "", "", "", "")));

        List<DropAppPodConfiguration> result = dropAppService.loadConfiguration(List.of("ns-x"), components);

        verify(dropAppClient).fetchConfiguration(List.of("ns-x"), components);
        assertThat(result).hasSize(1);
    }

    @Test
    void loadConfigurationDocumentsDelegatesToClient() {
        List<DropAppConfigurationComponent> components = List.of(
                DropAppConfigurationComponent.DEPLOYMENTS,
                DropAppConfigurationComponent.CONFIGMAPS);
        when(dropAppClient.fetchConfigurationDocuments(List.of("ns-y"), components))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        "ns-y",
                        "deployments",
                        "api",
                        "apiVersion: apps/v1\nkind: Deployment")));

        List<DropAppConfigurationSnapshotDocument> result =
                dropAppService.loadConfigurationDocuments(List.of("ns-y"), components);

        verify(dropAppClient).fetchConfigurationDocuments(List.of("ns-y"), components);
        assertThat(result).hasSize(1);
    }
}
