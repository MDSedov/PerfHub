package com.example.chronicler.registry;

import com.example.chronicler.exception.GigachatPromptLoadingException;
import com.example.chronicler.repository.GigachatPromptRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GigachatPromptRegistry {

    private final GigachatPromptRepository repository;
    private volatile Map<String, String> prompts;

    public GigachatPromptRegistry(GigachatPromptRepository repository) {
        this.repository = repository;
        this.prompts = loadPrompts();
    }

    public Optional<String> findPrompt(String key) {
        return Optional.ofNullable(prompts.get(key));
    }

    public Map<String, String> getAll() {
        return prompts;
    }

    public synchronized void reload() {
        this.prompts = loadPrompts();
    }

    private Map<String, String> loadPrompts() {
        Map<String, String> loaded = repository.findAll();
        if (loaded.isEmpty()) {
            throw new GigachatPromptLoadingException("No GigaChat prompts were found in the database");
        }
        return Map.copyOf(loaded);
    }
}
