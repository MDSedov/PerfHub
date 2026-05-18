package perfhub.client;

import perfhub.exception.VictoriaMetricsClientException;
import perfhub.service.ConfigurationProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class VictoriaMetricsClient {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsClient.class);
    private static final String SERVICE = "victoria-metrics";
    private static final String BASE_URL_KEY = "base-url";
    private static final String QUERY_RANGE_PATH_KEY = "query-range-path";
    private static final String DEFAULT_STEP_SECONDS_KEY = "default-step-seconds";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public VictoriaMetricsClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    public String queryRange(String query, Instant start, Instant end) {
        String baseUrl = required(BASE_URL_KEY);
        String queryRangePath = required(QUERY_RANGE_PATH_KEY);
        Duration defaultStep = resolveDuration(DEFAULT_STEP_SECONDS_KEY);

        URI uri = buildQueryRangeUri(baseUrl, queryRangePath, query, start, end, defaultStep);
        try {
            log.info(
                    "Querying VictoriaMetrics with expression '{}' from {} to {} via {}",
                    query,
                    start,
                    end,
                    uri);
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            return response.getBody() != null ? response.getBody() : "";
        } catch (RestClientException ex) {
            log.error(
                    "VictoriaMetrics request failed via {} (query='{}', start={}, end={})",
                    uri,
                    query,
                    start,
                    end,
                    ex);
            throw new VictoriaMetricsClientException("Failed to query VictoriaMetrics via " + uri, ex);
        }
    }

    private String required(String key) {
        return configurationProvider.getRequiredValue(SERVICE, key);
    }

    private Duration resolveDuration(String key) {
        String value = required(key);
        try {
            long seconds = Long.parseLong(value);
            Assert.isTrue(seconds > 0, "Duration must be positive");
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Configuration value for %s.%s must be a number but was '%s'"
                    .formatted(SERVICE, key, value));
        }
    }

    private URI buildQueryRangeUri(
            String baseUrl,
            String queryRangePath,
            String query,
            Instant start,
            Instant end,
            Duration step) {
        String sanitizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String sanitizedPath = queryRangePath.startsWith("/") ? queryRangePath : "/" + queryRangePath;
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return URI.create(
                sanitizedBase
                        + sanitizedPath
                        + "?query="
                        + encodedQuery
                        + "&start="
                        + start.getEpochSecond()
                        + "&end="
                        + end.getEpochSecond()
                        + "&step="
                        + step.getSeconds());
    }
}
