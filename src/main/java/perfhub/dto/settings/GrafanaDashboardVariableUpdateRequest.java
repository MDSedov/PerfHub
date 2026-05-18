package perfhub.dto.settings;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record GrafanaDashboardVariableUpdateRequest(
        @NotBlank String name,
        String label,
        String type,
        String value,
        String defaultValue,
        List<GrafanaVariableOptionDto> options) {}
