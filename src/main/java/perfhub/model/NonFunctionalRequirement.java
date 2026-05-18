package perfhub.model;

import com.fasterxml.jackson.databind.JsonNode;

public record NonFunctionalRequirement(
        int id,
        String code,
        RequirementMetricType metricType,
        double thresholdValue,
        String thresholdUnit,
        boolean active,
        JsonNode parameters,
        String descriptionRu) {}
