package com.example.chronicler.dto;

import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.RequirementMetricType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RequirementEvaluationView(
        int requirementId,
        String code,
        RequirementMetricType metricType,
        double thresholdValue,
        String thresholdUnit,
        RequirementEvaluationStatus status,
        Double measuredValue,
        String measuredUnit,
        JsonNode details,
        Instant evaluatedAt,
        String descriptionRu) {}
