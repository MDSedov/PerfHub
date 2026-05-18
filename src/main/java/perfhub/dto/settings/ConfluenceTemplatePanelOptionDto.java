package com.example.chronicler.dto.settings;

public record ConfluenceTemplatePanelOptionDto(
        String dashboardUid,
        int panelId,
        String code,
        String dashboardTitle,
        String panelTitle) {}
