package perfhub.dto.settings;

public record ConfluenceTemplateChartDto(
        String title,
        String dashboardUid,
        int panelId,
        String code) {}
