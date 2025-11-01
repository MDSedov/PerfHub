package com.example.chronicler.client;

import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.exception.GrafanaClientException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GrafanaClient {

    private static final Logger log = LoggerFactory.getLogger(GrafanaClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String renderPath;
    private final String apiKey;
    private final Integer imageWidth;
    private final Integer imageHeight;
    private final String timezone;

    public GrafanaClient(
            RestTemplate restTemplate,
            @Value("${grafana.base-url}") String baseUrl,
            @Value("${grafana.render-path}") String renderPath,
            @Value("${grafana.api-key}") String apiKey,
            @Value("${grafana.image-width}") Integer imageWidth,
            @Value("${grafana.image-height}") Integer imageHeight,
            @Value("${grafana.timezone}") String timezone) {
        Assert.hasText(baseUrl, "Grafana base URL must not be empty");
        Assert.hasText(renderPath, "Grafana render path must not be empty");
        Assert.hasText(apiKey, "Grafana API key must not be empty");
        Assert.notNull(imageWidth, "Grafana image width must not be null");
        Assert.notNull(imageHeight, "Grafana image height must not be null");
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.renderPath = renderPath;
        this.apiKey = apiKey;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.timezone = timezone;
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
        headers.setBearerAuth(apiKey);

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

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("api", "dashboards", "uid", dashboardUid)
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(apiKey);

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
     * Гарантирует наличие завершающего слэша в пути к API рендеринга.
     */
    private String ensurePathEndsWithSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
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
}
