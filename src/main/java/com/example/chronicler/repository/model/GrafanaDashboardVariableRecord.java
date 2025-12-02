package com.example.chronicler.repository.model;

import java.util.List;

public record GrafanaDashboardVariableRecord(
        String dashboardUid,
        String name,
        String label,
        String type,
        String defaultValue,
        String value,
        List<GrafanaVariableOptionRecord> options) {}
