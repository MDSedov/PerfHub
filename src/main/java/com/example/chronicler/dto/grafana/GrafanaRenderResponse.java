package com.example.chronicler.dto.grafana;

import java.util.List;

public record GrafanaRenderResponse(
        String from,
        String to,
        List<GrafanaRenderedDashboard> dashboards) {
}
