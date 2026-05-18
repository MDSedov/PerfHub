package perfhub.dto.settings;

import com.fasterxml.jackson.databind.JsonNode;

public record NonFunctionalRequirementDto(
        int id,
        String code,
        String metricType,
        double thresholdValue,
        String thresholdUnit,
        boolean active,
        JsonNode parameters,
        String descriptionRu) {}
