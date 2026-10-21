package perfhub.dto.grafana;

public record GrafanaRenderedPanel(int panelId, String title, String description, String imageBase64,
        Long templateChartId, String deployment, Integer templateChartOrder) {
    public GrafanaRenderedPanel(int panelId, String title, String description, String imageBase64) {
        this(panelId, title, description, imageBase64, null, null, null);
    }
}
