package com.example.chronicler.dto.grafana;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record GrafanaDashboardRequest(
        @NotBlank(message = "uid is required") String uid,
        @NotBlank(message = "slug is required") String slug,
        @NotEmpty(message = "panels is required") List<@Valid GrafanaPanelRequest> panels) {
}
