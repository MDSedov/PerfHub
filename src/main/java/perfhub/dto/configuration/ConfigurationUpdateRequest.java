package com.example.chronicler.dto.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ConfigurationUpdateRequest(
        @NotEmpty(message = "parameters must contain at least one entry")
        @Valid
        List<ConfigurationUpdateItemDto> parameters) {
}
