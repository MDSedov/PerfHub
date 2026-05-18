package com.example.chronicler.registry;

import com.example.chronicler.exception.GigachatPromptLoadingException;
import org.springframework.stereotype.Component;

@Component
public class GigachatTestsPromptProvider {

    private static final String TESTS_PROMPT_KEY = "tests";

    private final GigachatPromptRegistry promptRegistry;

    public GigachatTestsPromptProvider(GigachatPromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    public String loadPrompt() {
        return promptRegistry
                .findPrompt(TESTS_PROMPT_KEY)
                .orElseThrow(
                        () ->
                                new GigachatPromptLoadingException(
                                        "Failed to load GigaChat tests prompt"));
    }
}
