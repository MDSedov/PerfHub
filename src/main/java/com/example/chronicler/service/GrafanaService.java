package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.LoadTestRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaService {

    private final GrafanaClient grafanaClient;
    private final GrafanaRenderCache grafanaRenderCache;
    private final LoadTestRepository loadTestRepository;
    private final GrafanaDashboardRepository grafanaDashboardRepository;

    public GrafanaService(
            GrafanaClient grafanaClient,
            GrafanaRenderCache grafanaRenderCache,
            LoadTestRepository loadTestRepository,
            GrafanaDashboardRepository grafanaDashboardRepository) {
        this.grafanaClient = grafanaClient;
        this.grafanaRenderCache = grafanaRenderCache;
        this.loadTestRepository = loadTestRepository;
        this.grafanaDashboardRepository = grafanaDashboardRepository;
    }

    /**
     * Рендерит набор дашбордов Grafana за указанный интервал времени и возвращает подготовленный ответ.
     */
    public GrafanaRenderResponse renderPanels(GrafanaRenderRequest request) {
        Objects.requireNonNull(request, "Grafana render request must not be null");

        UUID testUuid = request.testUuid();
        if (testUuid == null) {
            throw new GrafanaRenderingException("testUuid must not be null");
        }

        LoadTest loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new GrafanaRenderingException("Test with UUID " + testUuid + " not found"));

        Instant from = loadTest.start();
        Instant to = loadTest.end();

        if (from == null || to == null) {
            throw new GrafanaInvalidTimeRangeException("Start and end time must be set for the test");
        }

        if (!to.isAfter(from)) {
            throw new GrafanaInvalidTimeRangeException("End time must be after start time");
        }

        List<GrafanaDashboard> dashboardConfigs = grafanaDashboardRepository.findAll();
        List<GrafanaDashboard> dashboardsWithPanels = dashboardConfigs.stream()
                .filter(config -> config.panels() != null && !config.panels().isEmpty())
                .toList();
        if (dashboardsWithPanels.isEmpty()) {
            throw new GrafanaRenderingException("No Grafana panels selected for export");
        }

        List<GrafanaRenderedDashboard> dashboards = new ArrayList<>();
        for (GrafanaDashboard dashboardConfig : dashboardsWithPanels) {
            String uid = requireText(dashboardConfig.uid(), "uid");
            String slug = requireText(dashboardConfig.slug(), "slug");
            List<Integer> panels = dashboardConfig.panels();

            List<GrafanaRenderedPanel> renderedPanels = new ArrayList<>(panels.size());
            for (Integer panelId : panels) {
                if (panelId == null || panelId <= 0) {
                    throw new GrafanaRenderingException("Panel identifiers must be positive numbers");
                }
                GrafanaPanelMetadata metadata = grafanaClient.getPanelMetadata(uid, panelId);
                String title = requireText(metadata.title(), "title");
                String description = metadata.description() == null ? "" : metadata.description();
                byte[] imageBytes = grafanaClient.renderPanel(uid, slug, panelId, from, to);
                String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
                renderedPanels.add(new GrafanaRenderedPanel(panelId, title, description, encodedImage));
            }

            dashboards.add(new GrafanaRenderedDashboard(uid, slug, renderedPanels));
        }

        GrafanaRenderResponse response = new GrafanaRenderResponse(from.toString(), to.toString(), dashboards);
        grafanaRenderCache.store(response);
        return response;
    }

    /**
     * Проверяет, что строка содержит непустое значение, иначе выбрасывает исключение рендеринга.
     */
    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new GrafanaRenderingException(field + " must not be blank");
        }
        return value;
    }
}
