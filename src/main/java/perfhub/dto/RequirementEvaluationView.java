package com.example.chronicler.dto;

import com.example.chronicler.model.RequirementEvaluationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RequirementEvaluationView(
        String dashboardUid,
        int panelId,
        String title,
        Double thresholdValue,
        RequirementEvaluationStatus status,
        Double measuredValue,
        String measuredUnit,
        JsonNode details,
        Instant evaluatedAt) {}
