package perfhub.dto.settings;

import java.util.List;

public record GrafanaDashboardVariableDto(
        String name,
        String label,
        String type,
        String value,
        String defaultValue,
        List<GrafanaVariableOptionDto> options) {}
