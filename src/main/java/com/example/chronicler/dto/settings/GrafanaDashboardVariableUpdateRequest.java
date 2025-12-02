package com.example.chronicler.dto.settings;

import jakarta.validation.constraints.NotBlank;

public record GrafanaDashboardVariableUpdateRequest(@NotBlank String name, String value) {}
