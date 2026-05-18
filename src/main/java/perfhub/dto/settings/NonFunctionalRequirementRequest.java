package com.example.chronicler.dto.settings;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NonFunctionalRequirementRequest(
        @NotBlank String code,
        @NotBlank String metricType,
        @NotNull Double thresholdValue,
        @NotBlank String thresholdUnit,
        String descriptionRu,
        @NotNull Boolean active,
        JsonNode parameters) {}
