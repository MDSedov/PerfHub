package perfhub.dto.grafana;

import java.util.List;

public record GrafanaVariableDefinition(
        String name,
        String label,
        String type,
        String defaultValue,
        String currentValue,
        List<GrafanaVariableOption> options) {}
