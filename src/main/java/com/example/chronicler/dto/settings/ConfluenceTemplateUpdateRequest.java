package com.example.chronicler.dto.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record ConfluenceTemplateUpdateRequest(
        @NotEmpty @Valid List<TemplateUpdatePayload> templates) {

    public record TemplateUpdatePayload(
            @Positive int id,
            @NotBlank String text) {}
}

