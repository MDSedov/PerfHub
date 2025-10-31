package com.example.chronicler.client;

import com.example.chronicler.exception.GrafanaClientException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

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
import org.springframework.util.StringUtils;
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
            @Value("${grafana.image-width}") int imageWidth,
            @Value("${grafana.image-height}") int imageHeight,
            @Value("${grafana.timezone}") String timezone) {
        Assert.hasText(baseUrl, "Grafana base URL must not be empty");
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.renderPath = StringUtils.hasText(renderPath) ? renderPath : "/render/d-solo";
        this.apiKey = apiKey;
        this.imageWidth = imageWidth > 0 ? imageWidth : null;
        this.imageHeight = imageHeight > 0 ? imageHeight : null;
        this.timezone = StringUtils.hasText(timezone) ? timezone : null;
    }

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
                .queryParam("to", to.toEpochMilli());

        if (imageWidth != null) {
            builder.queryParam("width", imageWidth);
        }
        if (imageHeight != null) {
            builder.queryParam("height", imageHeight);
        }
        if (timezone != null) {
            builder.queryParam("tz", timezone);
        }

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
        if (StringUtils.hasText(apiKey)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

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

    private String ensurePathEndsWithSlash(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }
}
