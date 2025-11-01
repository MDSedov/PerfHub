package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardRequest;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    private GrafanaService grafanaService;

    @BeforeEach
    void setUp() {
        grafanaService = new GrafanaService(grafanaClient, grafanaRenderCache);
    }

    @Test
    void renderDashboardsReturnsEncodedImages() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        String fromInput = "2024-01-01 00:00:00";
        String toInput = "2024-01-01 01:00:00";
        Instant from = LocalDateTime.parse(fromInput, formatter).atZone(moscow).toInstant();
        Instant to = LocalDateTime.parse(toInput, formatter).atZone(moscow).toInstant();

        GrafanaRenderRequest request = new GrafanaRenderRequest(
                fromInput,
                toInput,
                List.of(new GrafanaDashboardRequest(
                        "uid",
                        "slug",
                        List.of(1, 2))));

        when(grafanaClient.renderPanel("uid", "slug", 1, from, to)).thenReturn(new byte[] {1, 2});
        when(grafanaClient.renderPanel("uid", "slug", 2, from, to)).thenReturn(new byte[] {3});
        when(grafanaClient.getPanelMetadata("uid", 1)).thenReturn(new GrafanaPanelMetadata("Panel 1", "First panel"));
        when(grafanaClient.getPanelMetadata("uid", 2)).thenReturn(new GrafanaPanelMetadata("Panel 2", null));

        GrafanaRenderResponse response = grafanaService.renderDashboards(request);

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
    void renderDashboardsRejectsInvalidTimeRange() {
        GrafanaRenderRequest request = new GrafanaRenderRequest(
                "2024-01-01 02:00:00",
                "2024-01-01 01:00:00",
                List.of(new GrafanaDashboardRequest(
                        "uid", "slug", List.of(1))));

        assertThatThrownBy(() -> grafanaService.renderDashboards(request))
                .isInstanceOf(GrafanaInvalidTimeRangeException.class)
                .hasMessage("End time must be after start time");
    }

    @Test
    void renderDashboardsRejectsIncorrectFormat() {
        GrafanaRenderRequest request = new GrafanaRenderRequest(
                "2024-01-01T00:00:00Z",
                "2024-01-01 01:00:00",
                List.of(new GrafanaDashboardRequest(
                        "uid", "slug", List.of(1))));

        assertThatThrownBy(() -> grafanaService.renderDashboards(request))
                .isInstanceOf(GrafanaInvalidTimeRangeException.class)
                .hasMessage("Unable to parse from using pattern yyyy-MM-dd HH:mm:ss (Europe/Moscow)");
    }

    @Test
    void renderDashboardsRejectsMissingDashboards() {
        GrafanaRenderRequest request = new GrafanaRenderRequest(
                "2024-01-01T00:00:00Z",
                "2024-01-01T01:00:00Z",
                List.of());

        assertThatThrownBy(() -> grafanaService.renderDashboards(request))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("At least one dashboard must be provided");
    }
}
