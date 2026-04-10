package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationCaptureRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationCaptureResponse;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSaveRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotUpdateRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationViewDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.model.DropAppConfigurationSnapshot;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.model.DropAppConfigurationSnapshotItem;
import com.example.chronicler.repository.DropAppConfigurationSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropAppConfigurationSnapshotServiceTest {

    @Mock
    private DropAppConfigurationSnapshotRepository repository;

    @Mock
    private DropAppService dropAppService;

    private DropAppConfigurationSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new DropAppConfigurationSnapshotService(repository, dropAppService);
    }

    @Test
    void loadCurrentConfigurationReturnsResolvedNamespacesAndMaxRelease() {
        when(dropAppService.resolveNamespaces(List.of("ns-b"))).thenReturn(List.of("ns-b"));
        when(dropAppService.loadConfiguration(List.of("ns-b")))
                .thenReturn(List.of(
                        new DropAppPodConfiguration(
                                "ns-b", null, "pod-1", "container-1", "1.0.0", "2.12.9", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppPodConfiguration(
                                "ns-b", null, "pod-2", "container-2", "1.0.1", "2.12.10", "100m", "200m", "256Mi", "512Mi")));

        DropAppConfigurationViewDto result = service.loadCurrentConfiguration(List.of("ns-b"));

        assertThat(result.namespaces()).containsExactly("ns-b");
        assertThat(result.release()).isEqualTo("2.12.10");
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void saveSnapshotPersistsDataAndReturnsSavedDetails() {
        DropAppConfigurationItemDto item = new DropAppConfigurationItemDto(
                "ns-a", null, "pod-1", "container-1", "1.0.0", "2.12.1", "100m", "200m", "256Mi", "512Mi");
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(repository.insertSnapshot(eq("2.12.7"), eq("комментарий"), eq("ns-a"), eq(null), any(Instant.class)))
                .thenReturn(42L);
        when(repository.findSnapshotById(42L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        42L, "2.12.7", "комментарий", "ns-a", null, savedAt)));
        when(repository.findItemsBySnapshotId(42L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotItem(
                        1L, 42L, "ns-a", null, "pod-1", "container-1", "1.0.0", "2.12.1", "100m", "200m", "256Mi", "512Mi")));
        when(repository.findDocumentsBySnapshotId(42L)).thenReturn(List.of());

        DropAppConfigurationSnapshotDetailsDto result = service.saveSnapshot(
                new DropAppConfigurationSnapshotSaveRequest(List.of("ns-a"), "2.12.7", "комментарий", List.of(item)));

        verify(repository).saveItems(eq(42L), any());
        verify(repository).saveDocuments(eq(42L), any());
        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.release()).isEqualTo("2.12.7");
        assertThat(result.comment()).isEqualTo("комментарий");
        assertThat(result.namespaces()).containsExactly("ns-a");
        assertThat(result.components()).isEmpty();
        assertThat(result.items()).hasSize(1);
        assertThat(result.documents()).isEmpty();
    }

    @Test
    void captureCurrentConfigurationLoadsItemsSavesSnapshotAndReturnsSavedPayload() {
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(dropAppService.resolveNamespaces(List.of("ns-a"))).thenReturn(List.of("ns-a"));
        when(dropAppService.loadConfiguration(any(), any()))
                .thenReturn(List.of(
                        new DropAppPodConfiguration(
                                "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppPodConfiguration(
                                "ns-a", "ConfigMap", "cm-main", "Ключей: 4", "", "", "", "", "", "")));
        when(dropAppService.loadConfigurationDocuments(any(), any()))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        "ns-a",
                        "configmaps",
                        "cm-main",
                        "apiVersion: v1\nkind: ConfigMap")));
        when(repository.insertSnapshot(eq("2.12.7"), eq("для стенда A"), eq("ns-a"), eq("pods, configmaps"), any(Instant.class)))
                .thenReturn(55L);
        when(repository.findSnapshotById(55L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        55L, "2.12.7", "для стенда A", "ns-a", "pods, configmaps", savedAt)));
        when(repository.findItemsBySnapshotId(55L))
                .thenReturn(List.of(
                        new DropAppConfigurationSnapshotItem(
                                1L, 55L, "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppConfigurationSnapshotItem(
                                2L, 55L, "ns-a", "ConfigMap", "cm-main", "Ключей: 4", "", "", "", "", "", "")));
        when(repository.findDocumentsBySnapshotId(55L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        7L,
                        55L,
                        "ns-a",
                        "configmaps",
                        "cm-main",
                        "apiVersion: v1\nkind: ConfigMap")));

        DropAppConfigurationCaptureResponse result = service.captureCurrentConfiguration(
                new DropAppConfigurationCaptureRequest(
                        List.of("ns-a"),
                        List.of("pods", "configmaps"),
                        "для стенда A"));

        verify(repository).saveItems(eq(55L), any());
        verify(repository).saveDocuments(eq(55L), any());
        assertThat(result.snapshotId()).isEqualTo(55L);
        assertThat(result.savedAt()).isEqualTo("2026-04-03 13:15:30");
        assertThat(result.release()).isEqualTo("2.12.7");
        assertThat(result.comment()).isEqualTo("для стенда A");
        assertThat(result.namespaces()).containsExactly("ns-a");
        assertThat(result.components()).containsExactly("pods", "configmaps");
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void updateSnapshotReturnsUpdatedDetails() {
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(repository.findSnapshotById(77L))
                .thenReturn(
                        Optional.of(new DropAppConfigurationSnapshot(
                                77L,
                                "2.12.7",
                                "старый комментарий",
                                "ns-a",
                                "pods, services",
                                savedAt)),
                        Optional.of(new DropAppConfigurationSnapshot(
                                77L,
                                "2.12.8",
                                "новый комментарий",
                                "ns-a",
                                "pods, services",
                                savedAt)));
        when(repository.findItemsBySnapshotId(77L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotItem(
                        1L, 77L, "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi")));
        when(repository.findDocumentsBySnapshotId(77L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        9L, 77L, "ns-a", "services", "svc-main", "apiVersion: v1\nkind: Service")));

        DropAppConfigurationSnapshotDetailsDto result = service.updateSnapshot(
                77L,
                new DropAppConfigurationSnapshotUpdateRequest("2.12.8", "новый комментарий"));

        verify(repository).updateSnapshot(77L, "2.12.8", "новый комментарий");
        assertThat(result.release()).isEqualTo("2.12.8");
        assertThat(result.comment()).isEqualTo("новый комментарий");
        assertThat(result.components()).containsExactly("pods", "services");
        assertThat(result.documents()).hasSize(1);
    }
}
