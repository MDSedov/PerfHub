package com.example.chronicler.model;

public record ConfluenceTemplateChart(
        long id,
        int templateId,
        String title,
        String dashboardUid,
        int panelId,
        int sortOrder) {}
