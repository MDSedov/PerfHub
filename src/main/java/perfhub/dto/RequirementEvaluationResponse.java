package com.example.chronicler.dto;

import java.util.List;
import java.util.UUID;

public record RequirementEvaluationResponse(
        UUID testUuid,
        boolean compliant,
        List<RequirementEvaluationView> requirements) {}
