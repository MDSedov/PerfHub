package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
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
import java.util.LinkedHashMap;
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
    private final GrafanaVariableService grafanaVariableService;
    private final ConfluenceTemplateSettingsService confluenceTemplateSettingsService;

    public GrafanaService(
            GrafanaClient grafanaClient,
            GrafanaRenderCache grafanaRenderCache,
            LoadTestRepository loadTestRepository,
            GrafanaDashboardRepository grafanaDashboardRepository,
            GrafanaDashboardPanelRepository grafanaDashboardPanelRepository,
            GrafanaVariableService grafanaVariableService,
            ConfluenceTemplateSettingsService confluenceTemplateSettingsService) {
        this.grafanaClient = grafanaClient;
        this.grafanaRenderCache = grafanaRenderCache;
        this.loadTestRepository = loadTestRepository;
        this.grafanaDashboardRepository = grafanaDashboardRepository;
        this.grafanaDashboardPanelRepository = grafanaDashboardPanelRepository;
        this.grafanaVariableService = grafanaVariableService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
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

        return renderPanelsForTest(loadTest, from, to);
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
            Map<String, String> variableValues = grafanaVariableService.loadValues(selection.dashboardUid());
            Optional<GrafanaDashboardPanelRecord> cachedPanel =
                    grafanaDashboardPanelRepository.findByUidAndPanelId(selection.dashboardUid(), selection.panelId());
            String title = cachedPanel
                    .map(GrafanaDashboardPanelRecord::panelTitle)
                    .filter(StringUtils::hasText)
                    .orElse("Панель " + selection.panelId());
            byte[] imageBytes =
                    grafanaClient.renderPanel(selection.dashboardUid(), slug, selection.panelId(), from, to, variableValues);
            String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
            renderedPanels.add(new GrafanaRenderedPanel(selection.panelId(), title, "", encodedImage));
        }

        return List.copyOf(renderedPanels);
    }

    private GrafanaRenderResponse renderPanelsForTest(LoadTest loadTest, Instant from, Instant to) {
        ConfluenceTemplate template = resolveDefaultTemplate(loadTest);
        List<ConfluenceTemplateChart> templateCharts = template.charts() == null ? List.of() : template.charts();
        if (templateCharts.isEmpty()) {
            throw new GrafanaRenderingException(
                    "Для шаблона отчета '%s' не настроены графики".formatted(template.fullName()));
        }

        Map<String, List<ConfluenceTemplateChart>> chartsByDashboardUid = templateCharts.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ConfluenceTemplateChart::dashboardUid,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<GrafanaRenderedDashboard> dashboards = new ArrayList<>();
        for (Map.Entry<String, List<ConfluenceTemplateChart>> entry : chartsByDashboardUid.entrySet()) {
            String dashboardUid = requireText(entry.getKey(), "dashboardUid");
            GrafanaDashboard dashboard = grafanaDashboardRepository.findByUid(dashboardUid)
                    .orElseThrow(() -> new GrafanaRenderingException(
                            "Grafana dashboard " + dashboardUid + " not found"));
            String slug = requireText(dashboard.slug(), "slug");
            Map<String, String> variableValues = grafanaVariableService.loadValues(dashboardUid);
            Map<Integer, GrafanaDashboardPanelRecord> cachedPanelsById = grafanaDashboardPanelRepository
                    .findByDashboardUid(dashboardUid).stream()
                    .collect(Collectors.toMap(
                            GrafanaDashboardPanelRecord::panelId,
                            Function.identity(),
                            (first, second) -> first));

            List<GrafanaRenderedPanel> renderedPanels = new ArrayList<>(entry.getValue().size());
            for (ConfluenceTemplateChart chart : entry.getValue()) {
                if (chart.panelId() <= 0) {
                    throw new GrafanaRenderingException("Grafana panel selection is invalid");
                }
                ResolvedPanelMetadata metadata = resolvePanelMetadata(dashboardUid, chart.panelId(), cachedPanelsById.get(chart.panelId()));
                byte[] imageBytes = grafanaClient.renderPanel(dashboardUid, slug, chart.panelId(), from, to, variableValues);
                String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
                String title = StringUtils.hasText(chart.title()) ? chart.title().trim() : metadata.title();
                renderedPanels.add(new GrafanaRenderedPanel(
                        chart.panelId(),
                        title,
                        metadata.description(),
                        encodedImage));
            }
            dashboards.add(new GrafanaRenderedDashboard(dashboardUid, slug, List.copyOf(renderedPanels)));
        }

        GrafanaRenderResponse response = new GrafanaRenderResponse(from.toString(), to.toString(), List.copyOf(dashboards));
        grafanaRenderCache.store(response);
        return response;
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
            Map<String, String> variableValues = grafanaVariableService.loadValues(uid);
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
                byte[] imageBytes = grafanaClient.renderPanel(uid, slug, panelId, from, to, variableValues);
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

    private ConfluenceTemplate resolveDefaultTemplate(LoadTest loadTest) {
        try {
            return confluenceTemplateSettingsService.resolveDefaultTemplate(loadTest.type());
        } catch (IllegalArgumentException ex) {
            throw new GrafanaRenderingException(ex.getMessage());
        }
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
        String normalizedValue = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalizedValue)) {
            throw new GrafanaInvalidTimeRangeException(field + " must not be blank");
        }
        try {
            Optional<Instant> unixTimestamp = parseUnixTimestamp(normalizedValue);
            if (unixTimestamp.isPresent()) {
                return unixTimestamp.get();
            }
            return Instant.parse(normalizedValue);
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw new GrafanaInvalidTimeRangeException(
                    "Unable to parse " + field + " as ISO-8601 instant or Unix timestamp: " + value);
        }
    }

    private Optional<Instant> parseUnixTimestamp(String value) {
        String normalizedValue = value.startsWith("-") ? value.substring(1) : value;
        if (!normalizedValue.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        if (normalizedValue.length() == 10) {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(value)));
        }
        if (normalizedValue.length() == 13) {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
        }
        return Optional.empty();
    }

    private record ResolvedPanelMetadata(String title, String description) {}
}
