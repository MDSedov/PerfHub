package com.example.chronicler.dto.gigachat;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record GigachatRequest(
        @NotBlank(message = "promptKey is required") String promptKey,
        Map<String, String> variables) {
}
