package perfhub.dto;

import jakarta.validation.constraints.NotBlank;

public record VictoriaMetricsTestsRequest(
        @NotBlank(message = "start is required") String start,
        @NotBlank(message = "end is required") String end) {
}
