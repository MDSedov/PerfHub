package perfhub.dto;

import jakarta.validation.constraints.NotBlank;

public record VictoriaMetricsRequest(
        @NotBlank(message = "type is required") String type,
        @NotBlank(message = "start is required") String start,
        @NotBlank(message = "end is required") String end) {
}
