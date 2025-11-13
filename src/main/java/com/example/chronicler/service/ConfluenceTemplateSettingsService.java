package com.example.chronicler.service;

import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.repository.ConfluenceTemplateRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfluenceTemplateSettingsService {

    private final ConfluenceTemplateRepository templateRepository;

    public ConfluenceTemplateSettingsService(ConfluenceTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    public List<ConfluenceTemplate> listTemplates() {
        return templateRepository.findAllTemplates();
    }

    public List<ConfluenceTemplate> updateTemplates(List<TemplateUpdateCommand> updates) {
        for (TemplateUpdateCommand update : updates) {
            if (update == null) {
                continue;
            }
            if (!StringUtils.hasText(update.text())) {
                throw new IllegalArgumentException("Template body must not be blank");
            }
            templateRepository.updateTemplateText(update.id(), update.text());
        }
        return listTemplates();
    }

    public record TemplateUpdateCommand(int id, String text) {}
}
