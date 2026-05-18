package com.example.chronicler.client;

import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
import com.example.chronicler.dto.grafana.GrafanaVariableDefinition;
import com.example.chronicler.dto.grafana.GrafanaVariableOption;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.exception.GrafanaClientException;
import com.example.chronicler.service.ConfigurationProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GrafanaClient {

    private static final Logger log = LoggerFactory.getLogger(GrafanaClient.class);
    private static final String SERVICE = "grafana";
    private static final String BASE_URL_KEY = "base-url";
    private static final String RENDER_PATH_KEY = "render-path";
    private static final String API_KEY_KEY = "api-key";
    private static final String IMAGE_WIDTH_KEY = "image-width";
    private static final String IMAGE_HEIGHT_KEY = "image-height";
    private static final String TIMEZONE_KEY = "timezone";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public GrafanaClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    /**
     * Возвращает список дашбордов Grafana и их панелей.
     */
    public List<GrafanaDashboardDefinition> discoverDashboards() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(API_KEY_KEY));

        URI searchUri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "search")
                .queryParam("type", "dash-db")
                .build(true)
                .toUri();

        try {
            ResponseEntity<GrafanaSearchItem[]> searchResponse = restTemplate.exchange(
                    searchUri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaSearchItem[].class);
            GrafanaSearchItem[] dashboards = searchResponse.getBody();
            if (dashboards == null || dashboards.length == 0) {
                return List.of();
            }

            List<GrafanaDashboardDefinition> result = new ArrayList<>();
            for (GrafanaSearchItem item : dashboards) {
                if (item == null || !StringUtils.hasText(item.uid())) {
                    continue;
                }
                String slug = extractSlug(item.url());
                GrafanaDashboardDefinition definition = fetchDashboardDefinition(headers, item.uid(), slug, item.title());
                result.add(definition);
            }
            return List.copyOf(result);
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to list Grafana dashboards", ex);
        }
    }

    /**
     * Выполняет рендеринг панели Grafana и возвращает изображение в виде байтового массива.
     */
    public byte[] renderPanel(String dashboardUid, String dashboardSlug, int panelId, Instant from, Instant to) {
        return renderPanel(dashboardUid, dashboardSlug, panelId, from, to, Map.of());
    }

    /**
     * Выполняет рендеринг панели Grafana с учетом значений template variables.
     */
    public byte[] renderPanel(
            String dashboardUid,
            String dashboardSlug,
            int panelId,
            Instant from,
            Instant to,
            Map<String, String> variables) {
        requireText(dashboardUid, "dashboardUid must not be empty");
        requireText(dashboardSlug, "dashboardSlug must not be empty");
        requirePositive(panelId, "panelId must be positive");
        requireNonNull(from, "from must not be null");
        requireNonNull(to, "to must not be null");

        String baseUrl = required(BASE_URL_KEY);
        String renderPath = required(RENDER_PATH_KEY);
        int imageWidth = requiredInt(IMAGE_WIDTH_KEY);
        int imageHeight = requiredInt(IMAGE_HEIGHT_KEY);
        String timezone = required(TIMEZONE_KEY);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(ensurePathEndsWithSlash(renderPath))
                .pathSegment(dashboardUid, dashboardSlug)
                .queryParam("panelId", panelId)
                .queryParam("from", from.toEpochMilli())
                .queryParam("to", to.toEpochMilli())
                .queryParam("width", imageWidth)
                .queryParam("height", imageHeight)
                .queryParam("tz", timezone);
        appendVariables(builder, variables);

        // Кодируем query-параметры (включая var-*) как обычные значения, чтобы спецсимволы
        // в template variables не ломали URL и не приводили к 400 от Grafana.
        URI uri = builder.build().encode().toUri();
        log.info(
                "Requesting Grafana renderer for dashboard '{}' ({}) panel {} from {} to {}",
                dashboardSlug,
                dashboardUid,
                panelId,
                from,
                to);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.IMAGE_PNG, MediaType.ALL));
        headers.setBearerAuth(required(API_KEY_KEY));

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), byte[].class);
            byte[] body = response.getBody();
            if (body == null) {
                log.warn("Grafana renderer returned an empty body for {}", uri);
                return new byte[0];
            }
            return body;
        } catch (RestClientResponseException ex) {
            String message = "Failed to render Grafana panel %d for dashboard '%s' (%s): %s"
                    .formatted(panelId, dashboardSlug, dashboardUid, describeHttpFailure(ex));
            log.error("{} via {}", message, uri, ex);
            throw new GrafanaClientException(message, ex);
        } catch (RestClientException ex) {
            String message = "Failed to render Grafana panel %d for dashboard '%s' (%s): %s"
                    .formatted(panelId, dashboardSlug, dashboardUid, describeClientFailure(ex));
            log.error("{} via {}", message, uri, ex);
            throw new GrafanaClientException(message, ex);
        }
    }

    public String loadDashboardSlug(String dashboardUid) {
        requireText(dashboardUid, "dashboardUid must not be empty");

        URI uri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "dashboards", "uid", dashboardUid)
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(API_KEY_KEY));

        try {
            ResponseEntity<GrafanaDashboardResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaDashboardResponse.class);
            GrafanaDashboardResponse body = response.getBody();
            if (body == null) {
                throw new GrafanaClientException("Grafana dashboard response is empty for " + dashboardUid, null);
            }
            String slug = resolveDashboardSlug(body.meta(), null);
            if (!StringUtils.hasText(slug)) {
                throw new GrafanaClientException(
                        "Grafana dashboard " + dashboardUid + " does not contain slug metadata",
                        null);
            }
            return slug;
        } catch (RestClientResponseException ex) {
            throw new GrafanaClientException(
                    "Failed to load Grafana dashboard slug for '%s': %s"
                            .formatted(dashboardUid, describeHttpFailure(ex)),
                    ex);
        } catch (RestClientException ex) {
            throw new GrafanaClientException(
                    "Failed to load Grafana dashboard slug for '%s': %s"
                            .formatted(dashboardUid, describeClientFailure(ex)),
                    ex);
        }
    }

    /**
     * Добавляет значения переменных дашборда в query string в формате var-{name}={value}.
     */
    private void appendVariables(UriComponentsBuilder builder, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return;
        }
        variables.forEach((name, value) -> {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(value)) {
                return;
            }
            builder.queryParam("var-" + name, value);
        });
    }

    /**
     * Получает метаданные панели (заголовок и описание) по идентификатору панели и UID дашборда.
     */
    public GrafanaPanelMetadata getPanelMetadata(String dashboardUid, int panelId) {
        requireText(dashboardUid, "dashboardUid must not be empty");
        requirePositive(panelId, "panelId must be positive");

        URI uri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "dashboards", "uid", dashboardUid)
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(API_KEY_KEY));

        try {
            ResponseEntity<GrafanaDashboardResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaDashboardResponse.class);
            GrafanaDashboardResponse body = response.getBody();
            if (body == null || body.dashboard() == null) {
                throw new GrafanaClientException("Grafana dashboard response is empty", null);
            }

            Optional<GrafanaDashboardPanel> panel = body.dashboard().panelById(panelId);
            GrafanaDashboardPanel grafanaPanel = panel.orElseThrow(() ->
                    new GrafanaClientException("Grafana panel with id " + panelId + " not found", null));

            String description = grafanaPanel.description() == null ? "" : grafanaPanel.description();
            return new GrafanaPanelMetadata(grafanaPanel.title(), description);
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to retrieve Grafana panel metadata", ex);
        }
    }

    /**
     * Выполняет проверку доступности Grafana через эндпоинт /api/health.
     */
    public String checkHealth() {
        URI uri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "health")
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(API_KEY_KEY));

        try {
            ResponseEntity<GrafanaHealthResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaHealthResponse.class);
            GrafanaHealthResponse body = response.getBody();
            if (body == null) {
                throw new GrafanaClientException("Grafana health response was empty", null);
            }
            if (body.database() != null && !body.database().isBlank()) {
                return body.database();
            }
            return "status unknown";
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to check Grafana health", ex);
        }
    }

    private GrafanaDashboardDefinition fetchDashboardDefinition(
            HttpHeaders headers, String dashboardUid, String slug, String fallbackTitle) {
        URI uri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "dashboards", "uid", dashboardUid)
                .build(true)
                .toUri();

        try {
            ResponseEntity<GrafanaDashboardResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaDashboardResponse.class);
            GrafanaDashboardResponse body = response.getBody();
            String resolvedSlug = resolveDashboardSlug(body != null ? body.meta() : null, slug);
            if (body == null || body.dashboard() == null) {
                return new GrafanaDashboardDefinition(
                        dashboardUid, requireDashboardSlug(dashboardUid, resolvedSlug), fallbackTitle, List.of(), List.of());
            }

            String title = resolveDashboardTitle(body.dashboard().title(), fallbackTitle, resolvedSlug);
            List<GrafanaPanelDefinition> panels = extractPanels(body.dashboard());
            List<GrafanaVariableDefinition> variables = extractVariables(body.dashboard());

            return new GrafanaDashboardDefinition(
                    dashboardUid,
                    requireDashboardSlug(dashboardUid, resolvedSlug),
                    title,
                    panels,
                    variables);
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to load Grafana dashboard " + dashboardUid, ex);
        }
    }

    private String requireDashboardSlug(String dashboardUid, String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new GrafanaClientException("Grafana dashboard slug is missing for " + dashboardUid, null);
        }
        return slug;
    }

    private String resolveDashboardSlug(GrafanaDashboardMeta meta, String fallbackSlug) {
        if (meta != null) {
            if (StringUtils.hasText(meta.slug())) {
                return meta.slug();
            }
            if (StringUtils.hasText(meta.url())) {
                return extractSlug(meta.url());
            }
        }
        return fallbackSlug;
    }

    private String resolveDashboardTitle(String title, String fallbackTitle, String slug) {
        if (StringUtils.hasText(title)) {
            return title;
        }
        if (StringUtils.hasText(fallbackTitle)) {
            return fallbackTitle;
        }
        return slug;
    }

    private List<GrafanaPanelDefinition> extractPanels(GrafanaDashboard dashboard) {
        if (dashboard.panels() == null) {
            return List.of();
        }
        Map<Integer, GrafanaPanelDefinition> panelsById = new LinkedHashMap<>();
        for (GrafanaDashboardPanel panel : dashboard.panels()) {
            if (panel == null || panel.id() == null || panel.id() <= 0) {
                continue;
            }
            String title = panel.title();
            if (!StringUtils.hasText(title)) {
                title = "Панель " + panel.id();
            }
            String query = extractFirstQuery(panel.targets());
            Double threshold = extractFirstThreshold(panel.fieldConfig());
            panelsById.put(panel.id(), new GrafanaPanelDefinition(panel.id(), title, query, threshold));
        }
        return panelsById.values().stream().toList();
    }

    private List<GrafanaVariableDefinition> extractVariables(GrafanaDashboard dashboard) {
        GrafanaTemplating templating = dashboard.templating();
        if (templating == null || templating.list() == null || templating.list().isEmpty()) {
            return List.of();
        }
        List<GrafanaVariableDefinition> variables = new ArrayList<>();
        for (GrafanaVariable variable : templating.list()) {
            if (variable == null || !StringUtils.hasText(variable.name())) {
                continue;
            }
            List<GrafanaVariableOption> options = extractVariableOptions(variable);
            String currentValue = normalizeValue(variable.current() != null ? variable.current().value() : null);
            String defaultValue = StringUtils.hasText(currentValue)
                    ? currentValue
                    : findSelectedOption(options);
            variables.add(new GrafanaVariableDefinition(
                    variable.name(),
                    StringUtils.hasText(variable.label()) ? variable.label() : variable.name(),
                    variable.type(),
                    defaultValue,
                    currentValue,
                    options));
        }
        return List.copyOf(variables);
    }

    private List<GrafanaVariableOption> extractVariableOptions(GrafanaVariable variable) {
        if (variable.options() == null || variable.options().isEmpty()) {
            return List.of();
        }
        return variable.options().stream()
                .filter(Objects::nonNull)
                .map(option -> new GrafanaVariableOption(
                        normalizeValue(option.value()),
                        option.text(),
                        Boolean.TRUE.equals(option.selected())))
                .toList();
    }

    private String normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::normalizeValue)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    private String findSelectedOption(List<GrafanaVariableOption> options) {
        return options.stream()
                .filter(GrafanaVariableOption::selected)
                .map(GrafanaVariableOption::value)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String extractFirstQuery(List<GrafanaTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        for (GrafanaTarget target : targets) {
            if (target == null) {
                continue;
            }
            if (StringUtils.hasText(target.expr())) {
                return target.expr();
            }
            if (StringUtils.hasText(target.target())) {
                return target.target();
            }
        }
        return null;
    }

    private Double extractFirstThreshold(GrafanaFieldConfig fieldConfig) {
        if (fieldConfig == null || fieldConfig.defaults() == null || fieldConfig.defaults().thresholds() == null) {
            return null;
        }
        List<GrafanaThresholdStep> steps = fieldConfig.defaults().thresholds().steps();
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        // Если в Grafana только базовый шаг (обычно value = null или 0), считаем порог отсутствующим.
        if (steps.size() == 1) {
            Double value = steps.get(0) != null ? steps.get(0).value() : null;
            if (value == null || value == 0d) {
                return null;
            }
            return value;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            GrafanaThresholdStep step = steps.get(i);
            if (step != null && step.value() != null) {
                return step.value();
            }
        }
        return null;
    }

    private String extractSlug(String url) {
        if (!StringUtils.hasText(url)) {
            throw new GrafanaClientException("Grafana dashboard url is missing", null);
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == url.length() - 1) {
            throw new GrafanaClientException("Grafana dashboard url has no slug: " + url, null);
        }
        return url.substring(lastSlash + 1);
    }

    private String describeHttpFailure(RestClientResponseException ex) {
        String responseBody = abbreviateResponseBody(ex.getResponseBodyAsString());
        if (StringUtils.hasText(responseBody)) {
            return "HTTP %d %s. Response: %s"
                    .formatted(ex.getStatusCode().value(), ex.getStatusText(), responseBody);
        }
        return "HTTP %d %s".formatted(ex.getStatusCode().value(), ex.getStatusText());
    }

    private String describeClientFailure(RestClientException ex) {
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private String abbreviateResponseBody(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300) + "...";
    }

    /**
     * Гарантирует наличие завершающего слэша в пути к API рендеринга.
     */
    private String ensurePathEndsWithSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private String required(String key) {
        try {
            return configurationProvider.getRequiredValue(SERVICE, key);
        } catch (IllegalStateException ex) {
            throw new GrafanaClientException(
                    "Configuration parameter not found for service '%s' and key '%s'"
                            .formatted(SERVICE, key),
                    ex);
        }
    }

    private int requiredInt(String key) {
        String value = required(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new GrafanaClientException(
                    "Configuration value for %s.%s must be an integer but was '%s'"
                            .formatted(SERVICE, key, value),
                    ex);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new GrafanaClientException(message);
        }
    }

    private void requirePositive(int value, String message) {
        if (value <= 0) {
            throw new GrafanaClientException(message);
        }
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new GrafanaClientException(message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboardResponse(GrafanaDashboard dashboard, GrafanaDashboardMeta meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboardMeta(String slug, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboard(
            String title, GrafanaTemplating templating, List<GrafanaDashboardPanel> panels) {
        Optional<GrafanaDashboardPanel> panelById(int panelId) {
            if (panels == null) {
                return Optional.empty();
            }
            return panels.stream()
                    .filter(panel -> panel != null && panel.id() != null && panel.id() == panelId)
                    .findFirst();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboardPanel(
            Integer id,
            String title,
            String description,
            List<GrafanaTarget> targets,
            GrafanaFieldConfig fieldConfig) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaTarget(String expr, String target) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaFieldConfig(GrafanaDefaults defaults) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDefaults(GrafanaThresholds thresholds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaThresholds(List<GrafanaThresholdStep> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaThresholdStep(Double value, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaSearchItem(String uid, String url, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaTemplating(List<GrafanaVariable> list) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaVariable(
            String name,
            String label,
            String type,
            GrafanaVariableCurrent current,
            List<GrafanaVariableOptionResponse> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaVariableCurrent(Object value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaVariableOptionResponse(String text, Object value, Boolean selected) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaHealthResponse(String database, String message) {}
}
