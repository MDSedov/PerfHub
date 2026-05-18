package perfhub.dto.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfigurationUpdateItemDto(
        @NotBlank(message = "service is required") String service,
        @NotBlank(message = "key is required") String key,
        @NotNull(message = "value must be provided") String value) {
}
