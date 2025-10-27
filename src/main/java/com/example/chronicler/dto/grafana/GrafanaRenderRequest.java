package com.example.chronicler.dto.grafana;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record GrafanaRenderRequest(
        @NotBlank(message = "from is required") String from,
        @NotBlank(message = "to is required") String to,
        @NotEmpty(message = "dashboards is required") List<@Valid GrafanaDashboardRequest> dashboards) {
}
