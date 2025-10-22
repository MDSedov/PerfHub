package com.example.chronicler.client;

import com.example.chronicler.exception.VictoriaMetricsClientException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class VictoriaMetricsClient {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String queryRangePath;
    private final Duration defaultStep;

    public VictoriaMetricsClient(
            RestTemplate restTemplate,
            @Value("${vm.base-url}") String baseUrl,
            @Value("${vm.query-range-path:/api/v1/query_range}") String queryRangePath,
            @Value("${vm.default-step-seconds:60}") long defaultStepSeconds) {
        Assert.hasText(baseUrl, "VictoriaMetrics base URL must not be empty");
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.queryRangePath = queryRangePath;
        this.defaultStep = Duration.ofSeconds(Math.max(1, defaultStepSeconds));
    }

    public String queryRange(String query, Instant start, Instant end) {
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
}
