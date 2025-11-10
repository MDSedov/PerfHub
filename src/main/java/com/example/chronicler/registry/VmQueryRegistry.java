package com.example.chronicler.registry;

import com.example.chronicler.repository.VictoriaMetricsQueryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Loads VictoriaMetrics query templates from the database and exposes them
 * as an immutable map that can be searched by query code.
 */
@Component
public class VmQueryRegistry {

    private final Map<String, String> queries;

    @Autowired
    public VmQueryRegistry(VictoriaMetricsQueryRepository repository) {
        this(repository.findAll());
    }

    protected VmQueryRegistry(Map<String, String> queries) {
        this.queries = Map.copyOf(new LinkedHashMap<>(queries));
    }

    public Optional<String> findQuery(String type) {
        return Optional.ofNullable(queries.get(type));
    }

    public Map<String, String> getAll() {
        return queries;
    }
}
