package perfhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import perfhub.dto.dropapp.DropAppConfigurationItemDto;
import perfhub.dto.dropapp.DropAppConfigurationCaptureRequest;
import perfhub.dto.dropapp.DropAppConfigurationCaptureResponse;
import perfhub.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import perfhub.dto.dropapp.DropAppConfigurationSnapshotSaveRequest;
import perfhub.dto.dropapp.DropAppConfigurationSnapshotUpdateRequest;
import perfhub.dto.dropapp.DropAppConfigurationViewDto;
import perfhub.dto.dropapp.DropAppPodConfiguration;
import perfhub.dto.dropapp.DropAppNamespaceOptionsDto;
import perfhub.dto.dropapp.DropAppServiceOptionDto;
import perfhub.dto.dropapp.DropAppServiceSelectionDto;
import perfhub.client.JiraClient;
import perfhub.dto.jira.JiraReleaseDistribution;
import perfhub.dto.jira.JiraReleaseIssue;
import perfhub.exception.DropAppClientException;
import perfhub.model.DropAppConfigurationComponent;
import perfhub.model.DropAppConfigurationSnapshot;
import perfhub.model.DropAppConfigurationSnapshotDocument;
import perfhub.model.DropAppConfigurationSnapshotDistribution;
import perfhub.model.DropAppConfigurationSnapshotItem;
import perfhub.repository.DropAppConfigurationSnapshotRepository;
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

    @Mock
    private StandConfigurationSettingsService standConfigurationSettingsService;

    @Mock
    private JiraClient jiraClient;

    private DropAppConfigurationSnapshotService service;

    @BeforeEach
    void setUp() {
        lenient().when(standConfigurationSettingsService.resolveComponents())
                .thenReturn(DropAppConfigurationComponent.defaultSelection());
        lenient().when(standConfigurationSettingsService.resolveServices(any())).thenReturn(List.of());
        lenient().when(standConfigurationSettingsService.resolveNamespaces()).thenReturn(List.of("ns-a"));
        lenient().when(jiraClient.isConfiguredForReleaseLookup()).thenReturn(false);
        lenient().when(repository.findDistributionsBySnapshotId(any())).thenReturn(List.of());
        service = new DropAppConfigurationSnapshotService(
                repository,
                dropAppService,
                standConfigurationSettingsService,
                jiraClient);
    }

    @Test
    void getNamespaceOptionsFallsBackToConfiguredNamespacesWhenDropAppListFails() {
        when(dropAppService.getConfiguredNamespaces()).thenReturn(List.of("ns-b", "ns-a"));
        when(dropAppService.listNamespaces()).thenThrow(new DropAppClientException("Forbidden"));

        DropAppNamespaceOptionsDto result = service.getNamespaceOptions();

        assertThat(result.available()).containsExactly("ns-a", "ns-b");
        assertThat(result.selected()).containsExactly("ns-b", "ns-a");
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
    void getServiceOptionsReturnsGroupedServicesForResolvedNamespaces() {
        when(dropAppService.listServiceOptions(List.of("ns-a")))
                .thenReturn(List.of(
                        new DropAppServiceOptionDto("ns-a", "billing", 1, "2.12.7"),
                        new DropAppServiceOptionDto("ns-a", "orders", 1, "2.12.8")));

        List<DropAppServiceOptionDto> result = service.getServiceOptions(List.of("ns-a"));

        assertThat(result)
                .extracting(DropAppServiceOptionDto::serviceName)
                .containsExactly("billing", "orders");
        assertThat(result)
                .extracting(DropAppServiceOptionDto::release)
                .containsExactly("2.12.7", "2.12.8");
    }

    @Test
    void saveSnapshotPersistsDataAndReturnsSavedDetails() {
        DropAppConfigurationItemDto item = new DropAppConfigurationItemDto(
                "ns-a", null, "pod-1", "container-1", "1.0.0", "2.12.1", "100m", "200m", "256Mi", "512Mi");
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(repository.insertSnapshot(
                        isNull(), eq("2.12.7"), eq(null), eq(null), eq("комментарий"), eq("ns-a"), eq(null), any(Instant.class)))
                .thenReturn(42L);
        when(repository.findSnapshotById(42L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        42L, null, "2.12.7", null, null, "комментарий", "ns-a", null, savedAt)));
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
        when(dropAppService.loadConfiguration(any(), any(), any()))
                .thenReturn(List.of(
                        new DropAppPodConfiguration(
                                "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppPodConfiguration(
                                "ns-a", "ConfigMap", "cm-main", "Ключей: 4", "", "", "", "", "", "")));
        when(dropAppService.loadConfigurationDocuments(any(), any(), any()))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        "ns-a",
                        "configmaps",
                        "cm-main",
                        "apiVersion: v1\nkind: ConfigMap")));
        when(repository.insertSnapshot(
                        isNull(), eq("2.12.7"), eq(null), eq(null), eq("для стенда A"), eq("ns-a"), eq("pods, configmaps"), any(Instant.class)))
                .thenReturn(55L);
        when(repository.findSnapshotById(55L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        55L, null, "2.12.7", null, null, "для стенда A", "ns-a", "pods, configmaps", savedAt)));
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
    void captureCurrentConfigurationStoresJiraReleaseIssueWhenConfigured() {
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(dropAppService.resolveNamespaces(List.of("ns-a"))).thenReturn(List.of("ns-a"));
        when(dropAppService.loadConfiguration(any(), any(), any()))
                .thenReturn(List.of(new DropAppPodConfiguration(
                        "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.18.0", "100m", "200m", "256Mi", "512Mi")));
        when(dropAppService.loadConfigurationDocuments(any(), any(), any())).thenReturn(List.of());
        when(jiraClient.isConfiguredForReleaseLookup()).thenReturn(true);
        when(jiraClient.findReleaseIssueByRelease("2.18.0"))
                .thenReturn(Optional.of(new JiraReleaseIssue(
                        "EPROFILE-4151",
                        "https://jira.example/browse/EPROFILE-4151")));
        when(jiraClient.findReleaseDistributionsByRelease("2.18.0"))
                .thenReturn(List.of(new JiraReleaseDistribution(
                        "profile",
                        "https://dpm.example/service/profile",
                        "sp-02/017/00_5.profile",
                        "https://nexus.example/repository/sp-02/017/00_5.profile")));
        when(repository.insertSnapshot(
                        isNull(),
                        eq("2.18.0"),
                        eq("EPROFILE-4151"),
                        eq("https://jira.example/browse/EPROFILE-4151"),
                        eq(null),
                        eq("ns-a"),
                        eq("pods"),
                        any(Instant.class)))
                .thenReturn(57L);
        when(repository.findSnapshotById(57L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        57L,
                        null,
                        "2.18.0",
                        "EPROFILE-4151",
                        "https://jira.example/browse/EPROFILE-4151",
                        null,
                        "ns-a",
                        "pods",
                        savedAt)));
        when(repository.findItemsBySnapshotId(57L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotItem(
                        1L, 57L, "ns-a", "Pod", "pod-1", "container-1", "1.0.0", "2.18.0", "100m", "200m", "256Mi", "512Mi")));
        when(repository.findDocumentsBySnapshotId(57L)).thenReturn(List.of());
        when(repository.findDistributionsBySnapshotId(57L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDistribution(
                        1L,
                        57L,
                        "profile",
                        "https://dpm.example/service/profile",
                        "sp-02/017/00_5.profile",
                        "https://nexus.example/repository/sp-02/017/00_5.profile")));

        DropAppConfigurationCaptureResponse result = service.captureCurrentConfiguration(
                new DropAppConfigurationCaptureRequest(List.of("ns-a"), List.of("pods"), null));

        verify(jiraClient).findReleaseIssueByRelease("2.18.0");
        verify(jiraClient).findReleaseDistributionsByRelease("2.18.0");
        verify(repository).saveDistributions(eq(57L), argThat(distributions ->
                distributions.size() == 1
                        && "profile".equals(distributions.get(0).serviceName())
                        && "https://dpm.example/service/profile".equals(distributions.get(0).serviceUrl())
                        && "sp-02/017/00_5.profile".equals(distributions.get(0).distributionName())
                        && "https://nexus.example/repository/sp-02/017/00_5.profile"
                        .equals(distributions.get(0).distributionUrl())));
        assertThat(result.release()).isEqualTo("2.18.0");
        assertThat(result.jiraReleaseIssueKey()).isEqualTo("EPROFILE-4151");
        assertThat(result.jiraReleaseIssueUrl()).isEqualTo("https://jira.example/browse/EPROFILE-4151");
        assertThat(result.jiraReleaseDistributions())
                .extracting("serviceName", "serviceUrl", "distributionName", "distributionUrl")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "profile",
                        "https://dpm.example/service/profile",
                        "sp-02/017/00_5.profile",
                        "https://nexus.example/repository/sp-02/017/00_5.profile"));
    }

    @Test
    void captureCurrentConfigurationFiltersSelectedServicesBeforeResolvingRelease() {
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(dropAppService.resolveNamespaces(List.of("ns-a"))).thenReturn(List.of("ns-a"));
        when(dropAppService.loadConfiguration(any(), any(), any()))
                .thenReturn(List.of(
                        new DropAppPodConfiguration(
                                "ns-a", "billing", "billing-1", 1, "app", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppPodConfiguration(
                                "ns-a", "ConfigMap", "billing-config", null, "Ключей: 2", "", "2.12.7", "", "", "", "")));
        when(dropAppService.loadConfigurationDocuments(any(), any(), any()))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        "ns-a",
                        "configmaps",
                        "billing-config",
                        "apiVersion: v1\nkind: ConfigMap")));
        when(repository.insertSnapshot(
                        isNull(), eq("2.12.7"), eq(null), eq(null), eq(null), eq("ns-a"), eq("pods, configmaps"), any(Instant.class)))
                .thenReturn(56L);
        when(repository.findSnapshotById(56L))
                .thenReturn(Optional.of(new DropAppConfigurationSnapshot(
                        56L, null, "2.12.7", null, null, null, "ns-a", "pods, configmaps", savedAt)));
        when(repository.findItemsBySnapshotId(56L))
                .thenReturn(List.of(
                        new DropAppConfigurationSnapshotItem(
                                1L, 56L, "ns-a", "billing", null, 1, "app", "1.0.0", "2.12.7", "100m", "200m", "256Mi", "512Mi"),
                        new DropAppConfigurationSnapshotItem(
                                2L, 56L, "ns-a", "ConfigMap", "billing-config", null, "Ключей: 2", "", "2.12.7", "", "", "", "")));
        when(repository.findDocumentsBySnapshotId(56L))
                .thenReturn(List.of(new DropAppConfigurationSnapshotDocument(
                        8L,
                        56L,
                        "ns-a",
                        "configmaps",
                        "billing-config",
                        "apiVersion: v1\nkind: ConfigMap")));

        DropAppConfigurationCaptureResponse result = service.captureCurrentConfiguration(
                new DropAppConfigurationCaptureRequest(
                        List.of("ns-a"),
                        List.of("pods", "configmaps"),
                        null,
                        List.of(new DropAppServiceSelectionDto("ns-a", "billing"))));

        assertThat(result.release()).isEqualTo("2.12.7");
        assertThat(result.items())
                .extracting(DropAppConfigurationItemDto::serviceName)
                .containsExactly("billing", "ConfigMap");
        assertThat(result.items())
                .extracting(DropAppConfigurationItemDto::podName)
                .containsExactly(null, "billing-config");
    }

    @Test
    void updateSnapshotReturnsUpdatedDetails() {
        Instant savedAt = Instant.parse("2026-04-03T10:15:30Z");
        when(repository.findSnapshotById(77L))
                .thenReturn(
                        Optional.of(new DropAppConfigurationSnapshot(
                                77L,
                                null,
                                "2.12.7",
                                null,
                                null,
                                "старый комментарий",
                                "ns-a",
                                "pods, services",
                                savedAt)),
                        Optional.of(new DropAppConfigurationSnapshot(
                                77L,
                                null,
                                "2.12.8",
                                null,
                                null,
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

        verify(repository).updateSnapshot(77L, "2.12.8", null, null, "новый комментарий");
        assertThat(result.release()).isEqualTo("2.12.8");
        assertThat(result.comment()).isEqualTo("новый комментарий");
        assertThat(result.components()).containsExactly("pods", "services");
        assertThat(result.documents()).hasSize(1);
    }

    @Test
    void deleteSnapshotRemovesSavedConfiguration() {
        when(repository.deleteSnapshot(91L)).thenReturn(1);

        service.deleteSnapshot(91L);

        verify(repository).deleteSnapshot(91L);
    }
}
