package com.example.chronicler.client;

import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
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
                List<GrafanaPanelDefinition> panels = fetchPanels(headers, item.uid());
                result.add(new GrafanaDashboardDefinition(
                        item.uid(),
                        slug,
                        item.title(),
                        panels));
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
        Assert.hasText(dashboardUid, "dashboardUid must not be empty");
        Assert.hasText(dashboardSlug, "dashboardSlug must not be empty");
        Assert.isTrue(panelId > 0, "panelId must be positive");
        Assert.notNull(from, "from must not be null");
        Assert.notNull(to, "to must not be null");

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

        URI uri = builder.build(true).toUri();
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
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to render Grafana panel", ex);
        }
    }

    /**
     * Получает метаданные панели (заголовок и описание) по идентификатору панели и UID дашборда.
     */
    public GrafanaPanelMetadata getPanelMetadata(String dashboardUid, int panelId) {
        Assert.hasText(dashboardUid, "dashboardUid must not be empty");
        Assert.isTrue(panelId > 0, "panelId must be positive");

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

    private List<GrafanaPanelDefinition> fetchPanels(HttpHeaders headers, String dashboardUid) {
        URI uri = UriComponentsBuilder.fromHttpUrl(required(BASE_URL_KEY))
                .pathSegment("api", "dashboards", "uid", dashboardUid)
                .build(true)
                .toUri();

        try {
            ResponseEntity<GrafanaDashboardResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(null, headers), GrafanaDashboardResponse.class);
            GrafanaDashboardResponse body = response.getBody();
            if (body == null || body.dashboard() == null || body.dashboard().panels() == null) {
                return List.of();
            }

            Map<Integer, String> panelsById = new LinkedHashMap<>();
            for (GrafanaDashboardPanel panel : body.dashboard().panels()) {
                if (panel == null || panel.id() == null || panel.id() <= 0) {
                    continue;
                }
                String title = panel.title();
                if (!StringUtils.hasText(title)) {
                    title = "Панель " + panel.id();
                }
                panelsById.put(panel.id(), title);
            }

            return panelsById.entrySet().stream()
                    .map(entry -> new GrafanaPanelDefinition(entry.getKey(), entry.getValue()))
                    .toList();
        } catch (RestClientException ex) {
            throw new GrafanaClientException("Failed to load Grafana dashboard " + dashboardUid, ex);
        }
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

    /**
     * Гарантирует наличие завершающего слэша в пути к API рендеринга.
     */
    private String ensurePathEndsWithSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private String required(String key) {
        return configurationProvider.getRequiredValue(SERVICE, key);
    }

    private int requiredInt(String key) {
        String value = required(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Configuration value for %s.%s must be an integer but was '%s'"
                    .formatted(SERVICE, key, value));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboardResponse(GrafanaDashboard dashboard) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaDashboard(List<GrafanaDashboardPanel> panels) {
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
    private record GrafanaDashboardPanel(Integer id, String title, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaSearchItem(String uid, String url, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrafanaHealthResponse(String database, String message) {}
}
