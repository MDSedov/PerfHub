package com.example.chronicler.service;

import com.example.chronicler.repository.GrafanaDashboardVariableRepository;
import com.example.chronicler.repository.model.GrafanaDashboardVariableRecord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaVariableService {

    private final GrafanaDashboardVariableRepository variableRepository;

    public GrafanaVariableService(GrafanaDashboardVariableRepository variableRepository) {
        this.variableRepository = variableRepository;
    }

    public Map<String, String> loadValues(String dashboardUid) {
        if (!StringUtils.hasText(dashboardUid)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (GrafanaDashboardVariableRecord variable : variableRepository.findByDashboardUid(dashboardUid)) {
            if (variable == null || !StringUtils.hasText(variable.name())) {
                continue;
            }
            String value = resolveValue(variable);
            if (!StringUtils.hasText(value) || values.containsKey(variable.name())) {
                continue;
            }
            values.put(variable.name(), value);
        }
        if (values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(values);
    }

    public String applyVariables(String query, String dashboardUid) {
        Map<String, String> variables = loadValues(dashboardUid);
        return applyVariables(query, variables);
    }

    public String applyVariables(String query, Map<String, String> variables) {
        if (!StringUtils.hasText(query) || variables == null || variables.isEmpty()) {
            return query;
        }
        String resolved = query;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            resolved = replaceVariable(resolved, entry.getKey(), value);
        }
        return resolved;
    }

    private String resolveValue(GrafanaDashboardVariableRecord variable) {
        String value = normalizeValue(variable.value());
        if (StringUtils.hasText(value)) {
            return value;
        }
        return normalizeValue(variable.defaultValue());
    }

    private String normalizeValue(String value) {
        String normalized = StringUtils.trimWhitespace(value);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String replaceVariable(String query, String name, String value) {
        String withBraces = query.replace("${" + name + "}", value);
        String withDollar = withBraces.replace("$" + name, value);
        return withDollar.replace("[[" + name + "]]", value);
    }
}
