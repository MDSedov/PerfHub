package perfhub.dto.grafana;

import java.util.List;

public record GrafanaRenderedDashboard(
        String uid,
        String slug,
        List<GrafanaRenderedPanel> panels) {
}
