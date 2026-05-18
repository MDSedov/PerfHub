package com.example.chronicler.dto.grafana;

public record GrafanaRenderedPanel(int panelId, String title, String description, String imageBase64) {
}
