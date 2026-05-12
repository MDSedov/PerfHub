package com.example.chronicler.dto.settings;

import java.util.List;

public record ConfluenceTemplateDto(
        int id,
        String shortName,
        String fullName,
        String reportTitle,
        String templateType,
        String templatePageId,
        String text,
        List<ConfluenceTemplateChartDto> charts) {}
