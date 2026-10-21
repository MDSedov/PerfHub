package perfhub.dto.grafana;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GrafanaCatalogSnapshot(
        GrafanaRenderDashboard dashboard, String datasource, String namespace,
        Map<String, String> variables, List<GrafanaRenderDashboard.Option> deployments,
        Instant updatedAt, boolean stale, long ownerId, String sourceRevision) {
    public GrafanaCatalogSnapshot(GrafanaRenderDashboard dashboard, String datasource, String namespace,
            Map<String,String> variables, List<GrafanaRenderDashboard.Option> deployments, Instant updatedAt, boolean stale) {
        this(dashboard,datasource,namespace,variables,deployments,updatedAt,stale,0L,"");
    }
}
