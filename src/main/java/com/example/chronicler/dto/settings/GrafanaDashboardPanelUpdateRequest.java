package com.example.chronicler.dto.settings;

import jakarta.validation.constraints.NotNull;

public record GrafanaDashboardPanelUpdateRequest(
        @NotNull Integer panelId,
        @NotNull Boolean selected,
        String title,
        String query,
        Double threshold,
        Boolean testQuery) {}
