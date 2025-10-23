package com.example.chronicler.registry;

import com.example.chronicler.exception.GigachatPromptLoadingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

@Component
public class GigachatTestsPromptProvider {

    private final Resource promptResource;

    public GigachatTestsPromptProvider(
            @Value("${gigachat.tests.prompt-resource:file:./prompts/gigachat-tests-prompt.txt}")
                    Resource promptResource) {
        this.promptResource = promptResource;
    }

    public String loadPrompt() {
        try {
            byte[] bytes = FileCopyUtils.copyToByteArray(promptResource.getInputStream());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GigachatPromptLoadingException("Failed to load GigaChat tests prompt", ex);
        }
    }
}
