package com.example.chronicler.dto.settings;

import java.util.List;

public record GrafanaDashboardDetailsDto(
        String uid,
        String slug,
        String title,
        boolean enabled,
        List<GrafanaDashboardPanelDto> panels,
        List<GrafanaDashboardVariableDto> variables) {}
