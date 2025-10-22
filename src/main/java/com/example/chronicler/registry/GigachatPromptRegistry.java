package com.example.chronicler.registry;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

@Component
public class GigachatPromptRegistry {

    private final Map<String, String> prompts;

    public GigachatPromptRegistry(
            @Value("${gigachat.prompts-resource:classpath:gigachat-prompts.properties}")
                    Resource promptsResource)
            throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(promptsResource);
        Map<String, String> loadedPrompts = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            loadedPrompts.put(name, properties.getProperty(name));
        }
        this.prompts = Collections.unmodifiableMap(loadedPrompts);
    }

    public Optional<String> findPrompt(String key) {
        return Optional.ofNullable(prompts.get(key));
    }
}
