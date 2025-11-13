package com.example.chronicler.registry;

import com.example.chronicler.repository.VictoriaMetricsQueryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Loads VictoriaMetrics query templates from the database and exposes them
 * as an immutable map that can be searched by query code.
 */
@Component
public class VmQueryRegistry {

    private final VictoriaMetricsQueryRepository repository;
    private volatile Map<String, String> queries;

    public VmQueryRegistry(VictoriaMetricsQueryRepository repository) {
        this.repository = repository;
        this.queries = loadQueries();
    }

    public Optional<String> findQuery(String type) {
        return Optional.ofNullable(queries.get(type));
    }

    public Map<String, String> getAll() {
        return queries;
    }

    public synchronized void reload() {
        this.queries = loadQueries();
    }

    private Map<String, String> loadQueries() {
        return Map.copyOf(new LinkedHashMap<>(repository.findAll()));
    }
}
