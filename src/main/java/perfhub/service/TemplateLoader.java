package perfhub.service;

import perfhub.exception.TemplateLoadingException;
import perfhub.repository.ConfluenceTemplateRepository;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Loads Confluence report templates from the database. */
@Component
public class TemplateLoader {

    private final ConfluenceTemplateRepository templateRepository;

    public TemplateLoader(ConfluenceTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * Loads a template by its logical name. Templates are looked up in the {@code confluence_templates}
     * table and can be referenced with or without the {@code .txt} suffix.
     *
     * @param templateName the logical template name (with or without {@code .txt} extension)
     * @return the template contents as a UTF-8 encoded string
     * @throws TemplateLoadingException if the template cannot be found
     */
    public String loadTemplate(String templateName) {
        if (!StringUtils.hasText(templateName)) {
            throw new TemplateLoadingException("Template name must not be empty");
        }

        String normalizedName = normalizeTemplateName(templateName);
        return templateRepository.findTemplateBody(normalizedName)
                .orElseThrow(() -> new TemplateLoadingException(
                        "Template '" + normalizedName + "' was not found in confluence_templates table"));
    }

    private String normalizeTemplateName(String templateName) {
        String name = templateName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}
