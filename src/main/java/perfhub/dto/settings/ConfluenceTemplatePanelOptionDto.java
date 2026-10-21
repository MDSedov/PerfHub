package perfhub.dto.settings;

public record ConfluenceTemplatePanelOptionDto(
        String dashboardUid,
        int panelId,
        String code,
        String dashboardTitle,
        String panelTitle,
        String renderScope,
        java.util.List<perfhub.dto.grafana.GrafanaRenderDashboard.Option> deployments,
        String catalogStatus,
        java.time.Instant catalogUpdatedAt) {}
