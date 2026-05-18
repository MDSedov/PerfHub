package perfhub.dto.settings;

public record ConfluenceTemplatePanelOptionDto(
        String dashboardUid,
        int panelId,
        String code,
        String dashboardTitle,
        String panelTitle) {}
