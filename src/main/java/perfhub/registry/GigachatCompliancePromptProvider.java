package com.example.chronicler.registry;

import com.example.chronicler.exception.GigachatPromptLoadingException;
import org.springframework.stereotype.Component;

@Component
public class GigachatCompliancePromptProvider {

    private static final String COMPLIANCE_PROMPT_KEY = "test.compliance";

    private final GigachatPromptRegistry promptRegistry;

    public GigachatCompliancePromptProvider(GigachatPromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    public String loadPrompt() {
        return promptRegistry
                .findPrompt(COMPLIANCE_PROMPT_KEY)
                .orElseThrow(
                        () ->
                                new GigachatPromptLoadingException(
                                        "Failed to load GigaChat compliance prompt"));
    }
}
