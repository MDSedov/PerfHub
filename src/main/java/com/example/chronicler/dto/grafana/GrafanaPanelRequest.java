package com.example.chronicler.dto.grafana;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GrafanaPanelRequest(
        @NotNull(message = "panel id is required") @Positive(message = "panel id must be positive") Integer panelId,
        @NotBlank(message = "title is required") String title,
        String description) {
}
