package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaPanelSelection;
import com.example.chronicler.exception.GrafanaClientException;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderRangeRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaService {

    private static final Logger log = LoggerFactory.getLogger(GrafanaService.class);
    private final GrafanaClient grafanaClient;
    private final GrafanaRenderCache grafanaRenderCache;
    private final LoadTestRepository loadTestRepository;
    private final GrafanaDashboardRepository grafanaDashboardRepository;
    private final GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;

    public GrafanaService(
            GrafanaClient grafanaClient,
            GrafanaRenderCache grafanaRenderCache,
            LoadTestRepository loadTestRepository,
            GrafanaDashboardRepository grafanaDashboardRepository,
            GrafanaDashboardPanelRepository grafanaDashboardPanelRepository) {
        this.grafanaClient = grafanaClient;
        this.grafanaRenderCache = grafanaRenderCache;
        this.loadTestRepository = loadTestRepository;
        this.grafanaDashboardRepository = grafanaDashboardRepository;
        this.grafanaDashboardPanelRepository = grafanaDashboardPanelRepository;
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

        return renderPanelsForRange(from, to);
    }

    /**
     * Рендерит панели Grafana за произвольный временной диапазон.
     */
    public GrafanaRenderResponse renderPanelsByRange(GrafanaRenderRangeRequest request) {
        Objects.requireNonNull(request, "Grafana render range request must not be null");
        Instant from = parseInstant(request.from(), "from");
        Instant to = parseInstant(request.to(), "to");
        if (!to.isAfter(from)) {
            throw new GrafanaInvalidTimeRangeException("End time must be after start time");
        }

        return renderPanelsForRange(from, to);
    }

    public List<GrafanaRenderedPanel> renderSelectedPanels(
            Instant from,
            Instant to,
            List<GrafanaPanelSelection> panelSelections) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!to.isAfter(from)) {
            throw new GrafanaInvalidTimeRangeException("End time must be after start time");
        }
        if (panelSelections == null || panelSelections.isEmpty()) {
            return List.of();
        }

        List<GrafanaRenderedPanel> renderedPanels = new ArrayList<>(panelSelections.size());
        for (GrafanaPanelSelection selection : panelSelections) {
            if (selection == null || !StringUtils.hasText(selection.dashboardUid()) || selection.panelId() <= 0) {
                throw new GrafanaRenderingException("Grafana panel selection is invalid");
            }

            GrafanaDashboard dashboard = grafanaDashboardRepository.findByUid(selection.dashboardUid())
                    .orElseThrow(() -> new GrafanaRenderingException(
                            "Grafana dashboard " + selection.dashboardUid() + " not found"));
            String slug = requireText(dashboard.slug(), "slug");
            Optional<GrafanaDashboardPanelRecord> cachedPanel =
                    grafanaDashboardPanelRepository.findByUidAndPanelId(selection.dashboardUid(), selection.panelId());
            String title = cachedPanel
                    .map(GrafanaDashboardPanelRecord::panelTitle)
                    .filter(StringUtils::hasText)
                    .orElse("Панель " + selection.panelId());
            byte[] imageBytes =
                    grafanaClient.renderPanel(selection.dashboardUid(), slug, selection.panelId(), from, to);
            String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
            renderedPanels.add(new GrafanaRenderedPanel(selection.panelId(), title, "", encodedImage));
        }

        return List.copyOf(renderedPanels);
    }

    private GrafanaRenderResponse renderPanelsForRange(Instant from, Instant to) {
        List<GrafanaDashboard> enabledDashboards = grafanaDashboardRepository.findAll().stream()
                .filter(GrafanaDashboard::enabled)
                .toList();
        if (enabledDashboards.isEmpty()) {
            throw new GrafanaRenderingException("No enabled Grafana dashboards configured");
        }

        List<GrafanaDashboard> dashboardsWithPanels = enabledDashboards.stream()
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
            Map<Integer, GrafanaDashboardPanelRecord> cachedPanelsById = grafanaDashboardPanelRepository
                    .findByDashboardUid(uid).stream()
                    .collect(Collectors.toMap(
                            GrafanaDashboardPanelRecord::panelId,
                            Function.identity(),
                            (first, second) -> first));

            List<GrafanaRenderedPanel> renderedPanels = new ArrayList<>(panels.size());
            for (Integer panelId : panels) {
                if (panelId == null || panelId <= 0) {
                    throw new GrafanaRenderingException("Panel identifiers must be positive numbers");
                }
                ResolvedPanelMetadata metadata = resolvePanelMetadata(uid, panelId, cachedPanelsById.get(panelId));
                byte[] imageBytes = grafanaClient.renderPanel(uid, slug, panelId, from, to);
                String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
                renderedPanels.add(new GrafanaRenderedPanel(
                        panelId,
                        metadata.title(),
                        metadata.description(),
                        encodedImage));
            }

            dashboards.add(new GrafanaRenderedDashboard(uid, slug, renderedPanels));
        }

        GrafanaRenderResponse response = new GrafanaRenderResponse(from.toString(), to.toString(), dashboards);
        grafanaRenderCache.store(response);
        return response;
    }

    private ResolvedPanelMetadata resolvePanelMetadata(
            String dashboardUid,
            int panelId,
            GrafanaDashboardPanelRecord cachedPanel) {
        String fallbackTitle = cachedPanel != null && StringUtils.hasText(cachedPanel.panelTitle())
                ? cachedPanel.panelTitle()
                : "Панель " + panelId;
        try {
            GrafanaPanelMetadata metadata = grafanaClient.getPanelMetadata(dashboardUid, panelId);
            if (metadata == null) {
                log.warn(
                        "Grafana returned null panel metadata for dashboard '{}' panel {}. Falling back to cached title.",
                        dashboardUid,
                        panelId);
                return new ResolvedPanelMetadata(fallbackTitle, "");
            }
            String title = StringUtils.hasText(metadata.title()) ? metadata.title() : fallbackTitle;
            String description = StringUtils.hasText(metadata.description()) ? metadata.description() : "";
            return new ResolvedPanelMetadata(title, description);
        } catch (GrafanaClientException ex) {
            log.warn(
                    "Failed to retrieve Grafana metadata for dashboard '{}' panel {}. Falling back to cached title: {}",
                    dashboardUid,
                    panelId,
                    ex.getMessage());
            return new ResolvedPanelMetadata(fallbackTitle, "");
        }
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

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new GrafanaInvalidTimeRangeException(
                    "Unable to parse " + field + " as ISO-8601 instant: " + value);
        }
    }

    private record ResolvedPanelMetadata(String title, String description) {}
}
