package com.example.chronicler.dto.grafana;

import jakarta.validation.constraints.NotBlank;

public record GrafanaRenderRangeRequest(
        @NotBlank(message = "from is required") String from,
        @NotBlank(message = "to is required") String to) {
}
