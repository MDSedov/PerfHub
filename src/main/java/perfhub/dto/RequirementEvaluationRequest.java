package com.example.chronicler.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RequirementEvaluationRequest(@NotNull UUID testUuid) {}
