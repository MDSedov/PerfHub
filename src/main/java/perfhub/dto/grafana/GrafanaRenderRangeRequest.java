package com.example.chronicler.dto.grafana;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GrafanaRenderRangeRequest(
        @NotBlank(message = "from is required") String from,
        @NotBlank(message = "to is required") String to,
        @NotNull(message = "templateId is required") Integer templateId) {
}
