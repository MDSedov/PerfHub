package perfhub.model;

public record ConfluenceTemplateChart(
        long id,
        int templateId,
        String title,
        String dashboardUid,
        int panelId,
        int sortOrder,
        String renderScope,
        String deployment) {
    public ConfluenceTemplateChart(long id, int templateId, String title, String dashboardUid, int panelId, int sortOrder) {
        this(id, templateId, title, dashboardUid, panelId, sortOrder, null, null);
    }

    public boolean deploymentScoped() {
        return "DROPAPP_DEPLOYMENT".equals(renderScope);
    }

    public String displayTitle() {
        return deploymentScoped() ? title + " [deployment: " + deployment + "]" : title;
    }
}
