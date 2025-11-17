package com.example.chronicler.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record GrafanaDashboardUpdateRequest(
        @NotBlank String slug,
        @NotNull List<Integer> panels,
        @NotNull Boolean enabled,
        List<GrafanaDashboardPanelUpdateRequest> panelSettings) {}
