package com.example.chronicler.repository.model;

public record GrafanaDashboardPanelRecord(
        String dashboardUid,
        String dashboardSlug,
        String dashboardTitle,
        int panelId,
        String panelTitle,
        String panelQuery,
        Double panelThreshold,
        Boolean testQuery) {}
