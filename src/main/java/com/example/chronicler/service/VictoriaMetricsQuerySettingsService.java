package com.example.chronicler.service;

import com.example.chronicler.model.VictoriaMetricsQuery;
import com.example.chronicler.registry.VmQueryRegistry;
import com.example.chronicler.repository.VictoriaMetricsQueryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VictoriaMetricsQuerySettingsService {

    private final VictoriaMetricsQueryRepository repository;
    private final VmQueryRegistry vmQueryRegistry;

    public VictoriaMetricsQuerySettingsService(
            VictoriaMetricsQueryRepository repository,
            VmQueryRegistry vmQueryRegistry) {
        this.repository = repository;
        this.vmQueryRegistry = vmQueryRegistry;
    }

    public List<VictoriaMetricsQuery> listQueries() {
        return repository.findAllDetailed();
    }

    public VictoriaMetricsQuery create(String code, String expression, String description) {
        VictoriaMetricsQuery query =
                repository.insert(normalizeCode(code), normalizeExpression(expression), normalizeDescription(description));
        vmQueryRegistry.reload();
        return query;
    }

    public VictoriaMetricsQuery update(long id, String code, String expression, String description) {
        VictoriaMetricsQuery query =
                repository.update(id, normalizeCode(code), normalizeExpression(expression), normalizeDescription(description));
        vmQueryRegistry.reload();
        return query;
    }

    public void delete(long id) {
        repository.delete(id);
        vmQueryRegistry.reload();
    }

    private String normalizeCode(String code) {
        String value = StringUtils.trimWhitespace(code);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Query code must not be blank");
        }
        return value;
    }

    private String normalizeExpression(String expression) {
        String value = StringUtils.trimWhitespace(expression);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Query expression must not be blank");
        }
        return value;
    }

    private String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return null;
        }
        return description.trim();
    }
}
