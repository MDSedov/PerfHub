package com.example.chronicler.dto.settings;

import jakarta.validation.constraints.NotBlank;

public record VictoriaMetricsQueryRequest(
        @NotBlank String code,
        @NotBlank String expression,
        String description) {}

