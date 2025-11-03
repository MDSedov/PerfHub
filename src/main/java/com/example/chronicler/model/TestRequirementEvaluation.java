package com.example.chronicler.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record TestRequirementEvaluation(
        long id,
        UUID testUuid,
        int requirementId,
        RequirementEvaluationStatus status,
        Double measuredValue,
        String unit,
        JsonNode details,
        Instant evaluatedAt) {}
