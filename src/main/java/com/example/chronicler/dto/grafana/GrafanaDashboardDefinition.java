package com.example.chronicler.dto.grafana;

import java.util.List;

public record GrafanaDashboardDefinition(
        String uid,
        String slug,
        String title,
        List<GrafanaPanelDefinition> panels,
        List<GrafanaVariableDefinition> variables) {}
