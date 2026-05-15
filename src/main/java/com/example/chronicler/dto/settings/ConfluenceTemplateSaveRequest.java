package com.example.chronicler.dto.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ConfluenceTemplateSaveRequest(
        @Size(max = 30) String shortName,
        String fullName,
        @NotBlank String reportTitle,
        @NotBlank String templateType,
        String testType,
        String templatePageId,
        String text,
        @Valid List<TemplateChartPayload> charts) {

    public record TemplateChartPayload(
            @NotBlank String title,
            @NotBlank String dashboardUid,
            @Positive int panelId) {}
}
