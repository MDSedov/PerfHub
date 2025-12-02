package com.example.chronicler.service;

import com.example.chronicler.repository.GrafanaDashboardVariableRepository;
import com.example.chronicler.repository.model.GrafanaDashboardVariableRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
        return variableRepository.findByDashboardUid(dashboardUid).stream()
                .collect(Collectors.toMap(
                        GrafanaDashboardVariableRecord::name,
                        this::resolveValue,
                        (first, second) -> first,
                        LinkedHashMap::new));
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
        String value = StringUtils.trimWhitespace(variable.value());
        if (StringUtils.hasText(value)) {
            return value;
        }
        return StringUtils.trimWhitespace(variable.defaultValue());
    }

    private String replaceVariable(String query, String name, String value) {
        String withBraces = query.replace("${" + name + "}", value);
        String withDollar = withBraces.replace("$" + name, value);
        return withDollar.replace("[[" + name + "]]", value);
    }
}
