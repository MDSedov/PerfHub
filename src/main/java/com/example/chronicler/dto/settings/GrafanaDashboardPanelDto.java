package com.example.chronicler.dto.settings;

public record GrafanaDashboardPanelDto(
        int panelId, String title, boolean selected, String query, Double threshold, boolean testQuery) {}
