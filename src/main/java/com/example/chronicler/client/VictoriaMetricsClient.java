package com.example.chronicler.client;

import com.example.chronicler.exception.VictoriaMetricsClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

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

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(queryRangePath)
                .queryParam("query", query)
                .queryParam("start", start.getEpochSecond())
                .queryParam("end", end.getEpochSecond())
                .queryParam("step", defaultStep.getSeconds());
        try {
            URI uri = builder.build(true).toUri();
            log.info(
                    "Querying VictoriaMetrics with expression '{}' from {} to {} via {}",
                    query,
                    start,
                    end,
                    uri);
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            return response.getBody() != null ? response.getBody() : "";
        } catch (RestClientException ex) {
            throw new VictoriaMetricsClientException("Failed to query VictoriaMetrics", ex);
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
}
