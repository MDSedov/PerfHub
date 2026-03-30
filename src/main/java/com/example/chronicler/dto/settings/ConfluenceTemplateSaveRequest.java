package com.example.chronicler.dto.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ConfluenceTemplateSaveRequest(
        @NotBlank @Size(max = 12) String shortName,
        @NotBlank String fullName,
        @NotBlank String reportTitle,
        @NotBlank String text,
        @Valid List<TemplateChartPayload> charts) {

    public record TemplateChartPayload(
            @NotBlank String title,
            @NotBlank String dashboardUid,
            @Positive int panelId) {}
}
