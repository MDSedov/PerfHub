package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.registry.VmQueryRegistry;
import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class VictoriaMetricsService {

    private final VmQueryRegistry queryRegistry;
    private final VictoriaMetricsClient client;

    VictoriaMetricsService(VmQueryRegistry queryRegistry, VictoriaMetricsClient client) {
        this.queryRegistry = queryRegistry;
        this.client = client;
    }

    public String fetchMetric(String type, Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new InvalidTimeRangeException("End time must be after start time");
        }
        String query = queryRegistry.findQuery(type)
                .orElseThrow(() -> new VmQueryNotFoundException(type));
        return client.queryRange(query, start, end);
    }
}
