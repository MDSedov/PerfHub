package com.example.chronicler.vm;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

/**
 * Loads VictoriaMetrics query templates from a dedicated properties file and
 * exposes them as an immutable map that can be searched by query type.
 */
@Component
public class VmQueryRegistry {

    private final Map<String, String> queries;

    public VmQueryRegistry(@Value("classpath:vm-queries.properties") Resource vmQueriesResource)
            throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(vmQueriesResource);
        Map<String, String> loadedQueries = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            loadedQueries.put(name, properties.getProperty(name));
        }
        this.queries = Collections.unmodifiableMap(loadedQueries);
    }

    public Optional<String> findQuery(String type) {
        return Optional.ofNullable(queries.get(type));
    }

    public Map<String, String> getAll() {
        return queries;
    }
}
