package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import java.time.Instant;
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

    private GrafanaService grafanaService;

    @BeforeEach
    void setUp() {
        grafanaService = new GrafanaService(grafanaClient);
    }

    @Test
    void renderDashboardsReturnsEncodedImages() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        GrafanaRenderRequest request = new GrafanaRenderRequest(
                from.toString(),
                to.toString(),
                List.of(new GrafanaDashboardRequest("uid", "slug", List.of(1, 2))));

        when(grafanaClient.renderPanel("uid", "slug", 1, from, to)).thenReturn(new byte[] {1, 2});
        when(grafanaClient.renderPanel("uid", "slug", 2, from, to)).thenReturn(new byte[] {3});

        GrafanaRenderResponse response = grafanaService.renderDashboards(request);

        assertThat(response.from()).isEqualTo(from.toString());
        assertThat(response.to()).isEqualTo(to.toString());
        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).uid()).isEqualTo("uid");
        assertThat(response.dashboards().get(0).slug()).isEqualTo("slug");
        assertThat(response.dashboards().get(0).panels()).hasSize(2);
        assertThat(response.dashboards().get(0).panels().get(0).panelId()).isEqualTo(1);
        assertThat(response.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("AQI=");
        assertThat(response.dashboards().get(0).panels().get(1).panelId()).isEqualTo(2);
        assertThat(response.dashboards().get(0).panels().get(1).imageBase64()).isEqualTo("Aw==");

        verify(grafanaClient).renderPanel("uid", "slug", 1, from, to);
        verify(grafanaClient).renderPanel("uid", "slug", 2, from, to);
    }

    @Test
    void renderDashboardsRejectsInvalidTimeRange() {
        GrafanaRenderRequest request = new GrafanaRenderRequest(
                "2024-01-01T02:00:00Z",
                "2024-01-01T01:00:00Z",
                List.of(new GrafanaDashboardRequest("uid", "slug", List.of(1))));

        assertThatThrownBy(() -> grafanaService.renderDashboards(request))
                .isInstanceOf(GrafanaInvalidTimeRangeException.class)
                .hasMessage("End time must be after start time");
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
