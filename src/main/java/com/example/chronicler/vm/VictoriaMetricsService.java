package com.example.chronicler.vm;

import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
class VictoriaMetricsService {

    private final VmQueryRegistry queryRegistry;
    private final VictoriaMetricsClient client;

    VictoriaMetricsService(VmQueryRegistry queryRegistry, VictoriaMetricsClient client) {
        this.queryRegistry = queryRegistry;
        this.client = client;
    }

    String fetchMetric(String type, Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new InvalidTimeRangeException("End time must be after start time");
        }
        String query = queryRegistry.findQuery(type)
                .orElseThrow(() -> new VmQueryNotFoundException(type));
        return client.queryRange(query, start, end);
    }
}
