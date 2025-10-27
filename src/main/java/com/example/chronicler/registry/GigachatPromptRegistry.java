package com.example.chronicler.registry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class GigachatPromptRegistry {

    private final Map<String, String> prompts;

    public GigachatPromptRegistry(
            @Value("${gigachat.prompts-directory:file:configs/prompts}") Resource promptsDirectory)
            throws IOException {
        File directory = promptsDirectory.getFile();
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("Prompt directory not found: " + promptsDirectory);
        }

        Path directoryPath = directory.toPath();
        List<Path> files;
        try (var paths = Files.list(directoryPath)) {
            files = paths.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException ex) {
            throw new IOException("Failed to list prompt files in " + directoryPath, ex);
        }

        Map<String, String> loadedPrompts = new HashMap<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            String key = extractKey(filename);
            String prompt = Files.readString(file, StandardCharsets.UTF_8);
            loadedPrompts.put(key, prompt);
        }
        this.prompts = Collections.unmodifiableMap(loadedPrompts);
    }

    public Optional<String> findPrompt(String key) {
        return Optional.ofNullable(prompts.get(key));
    }

    private String extractKey(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}
