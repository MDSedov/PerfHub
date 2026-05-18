package perfhub.dto.grafana;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GrafanaRenderPanelRangeRequest(
        @NotBlank(message = "from is required") String from,
        @NotBlank(message = "to is required") String to,
        @NotNull(message = "templateId is required") Integer templateId,
        @NotBlank(message = "dashboardUid is required") String dashboardUid,
        @Min(value = 1, message = "panelId must be positive") int panelId) {
}
