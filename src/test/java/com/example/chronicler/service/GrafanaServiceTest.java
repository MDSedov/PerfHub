package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.LoadTestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrafanaServiceTest {

    @Mock
    private GrafanaClient grafanaClient;

    @Mock
    private GrafanaRenderCache grafanaRenderCache;

    @Mock
    private LoadTestRepository loadTestRepository;

    @Mock
    private GrafanaDashboardRepository grafanaDashboardRepository;

    private GrafanaService grafanaService;

    @BeforeEach
    void setUp() {
        grafanaService = new GrafanaService(
                grafanaClient, grafanaRenderCache, loadTestRepository, grafanaDashboardRepository);
    }

    @Test
    void renderPanelsReturnsEncodedImages() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, from, to)));
        when(grafanaDashboardRepository.findAll())
                .thenReturn(List.of(new GrafanaDashboard("uid", "slug", List.of(1, 2))));
        when(grafanaClient.renderPanel("uid", "slug", 1, from, to)).thenReturn(new byte[] {1, 2});
        when(grafanaClient.renderPanel("uid", "slug", 2, from, to)).thenReturn(new byte[] {3});
        when(grafanaClient.getPanelMetadata("uid", 1)).thenReturn(new GrafanaPanelMetadata("Panel 1", "First panel"));
        when(grafanaClient.getPanelMetadata("uid", 2)).thenReturn(new GrafanaPanelMetadata("Panel 2", null));

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.from()).isEqualTo(from.toString());
        assertThat(response.to()).isEqualTo(to.toString());
        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).uid()).isEqualTo("uid");
        assertThat(response.dashboards().get(0).slug()).isEqualTo("slug");
        assertThat(response.dashboards().get(0).panels()).hasSize(2);
        assertThat(response.dashboards().get(0).panels().get(0).panelId()).isEqualTo(1);
        assertThat(response.dashboards().get(0).panels().get(0).title()).isEqualTo("Panel 1");
        assertThat(response.dashboards().get(0).panels().get(0).description()).isEqualTo("First panel");
        assertThat(response.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("AQI=");
        assertThat(response.dashboards().get(0).panels().get(1).panelId()).isEqualTo(2);
        assertThat(response.dashboards().get(0).panels().get(1).title()).isEqualTo("Panel 2");
        assertThat(response.dashboards().get(0).panels().get(1).description()).isEmpty();
        assertThat(response.dashboards().get(0).panels().get(1).imageBase64()).isEqualTo("Aw==");

        verify(grafanaClient).renderPanel("uid", "slug", 1, from, to);
        verify(grafanaClient).renderPanel("uid", "slug", 2, from, to);
        verify(grafanaClient).getPanelMetadata("uid", 1);
        verify(grafanaClient).getPanelMetadata("uid", 2);
        verify(grafanaRenderCache).store(response);
    }

    @Test
    void renderPanelsThrowsWhenTestNotFound() {
        UUID testUuid = UUID.randomUUID();
        when(loadTestRepository.findById(testUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessageContaining("Test with UUID " + testUuid + " not found");
    }

    @Test
    void renderPanelsThrowsWhenDashboardsMissing() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, from, to)));
        when(grafanaDashboardRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("No Grafana dashboards configured");
    }

    @Test
    void renderPanelsThrowsWhenTimeRangeInvalid() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T02:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, from, to)));
        when(grafanaDashboardRepository.findAll())
                .thenReturn(List.of(new GrafanaDashboard("uid", "slug", List.of(1))));

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaInvalidTimeRangeException.class)
                .hasMessage("End time must be after start time");
    }
}
