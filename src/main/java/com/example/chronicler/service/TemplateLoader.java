package com.example.chronicler.service;

import com.example.chronicler.exception.TemplateLoadingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Loads Confluence report templates from the configuration directory.
 */
@Component
public class TemplateLoader {

    private static final Path TEMPLATE_DIRECTORY = Path.of("config", "template");
    private static final Path TENPLATE_DIRECTORY = Path.of("config", "tenplate");

    /**
     * Loads a template by its logical name. Templates are expected to be stored either in
     * {@code config/template} or {@code config/tenplate} and use the {@code .txt} extension.
     *
     * @param templateName the logical template name (with or without {@code .txt} extension)
     * @return the template contents as a UTF-8 encoded string
     * @throws TemplateLoadingException if the template cannot be found or read
     */
    public String loadTemplate(String templateName) {
        if (!StringUtils.hasText(templateName)) {
            throw new TemplateLoadingException("Template name must not be empty");
        }

        String fileName = templateName.endsWith(".txt") ? templateName : templateName + ".txt";

        Path templatePath = resolveTemplatePath(fileName);
        try {
            return Files.readString(templatePath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new TemplateLoadingException(
                    "Failed to read template '" + fileName + "' from " + templatePath, ex);
        }
    }

    private Path resolveTemplatePath(String fileName) {
        Path primary = TEMPLATE_DIRECTORY.resolve(fileName);
        if (Files.exists(primary)) {
            return primary;
        }

        Path secondary = TENPLATE_DIRECTORY.resolve(fileName);
        if (Files.exists(secondary)) {
            return secondary;
        }

        throw new TemplateLoadingException(
                "Template '" + fileName + "' was not found in "
                        + TEMPLATE_DIRECTORY
                        + " or "
                        + TENPLATE_DIRECTORY);
    }
}
