package com.example.chronicler.vm;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class VictoriaMetricsClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String queryRangePath;
    private final Duration defaultStep;

    VictoriaMetricsClient(
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

    String queryRange(String query, Instant start, Instant end) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(queryRangePath)
                .queryParam("query", query)
                .queryParam("start", start.getEpochSecond())
                .queryParam("end", end.getEpochSecond())
                .queryParam("step", defaultStep.getSeconds());
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(builder.build(true).toUri(), String.class);
            return response.getBody() != null ? response.getBody() : "";
        } catch (RestClientException ex) {
            throw new VictoriaMetricsClientException("Failed to query VictoriaMetrics", ex);
        }
    }
}
