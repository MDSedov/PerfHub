package perfhub.dto.grafana;

import java.util.List;
import java.util.Map;

public record GrafanaRenderDashboard(
        String uid, String slug, String title, List<Panel> panels, Map<String, Variable> variables) {
    public record Panel(int id, String elementRef, String title, String description,
                        boolean deploymentScoped, String fingerprint) {}
    public record Variable(String name, String type, String datasource, String query, int queryType,
                           String regex, String regexApplyTo, boolean multi, boolean includeAll,
                           String allValue, String current) {}
    public record Option(String text, String value) {}
}
