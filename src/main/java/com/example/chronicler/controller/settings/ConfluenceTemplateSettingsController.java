package com.example.chronicler.controller.settings;

import com.example.chronicler.dto.settings.ConfluenceTemplateDto;
import com.example.chronicler.dto.settings.ConfluenceTemplateUpdateRequest;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.service.ConfluenceTemplateSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        path = "/api/settings/confluence-templates",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfluenceTemplateSettingsController {

    private final ConfluenceTemplateSettingsService settingsService;

    public ConfluenceTemplateSettingsController(ConfluenceTemplateSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public List<ConfluenceTemplateDto> listTemplates() {
        return settingsService.listTemplates().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<ConfluenceTemplateDto> updateTemplates(
            @Valid @RequestBody ConfluenceTemplateUpdateRequest request) {
        List<ConfluenceTemplateSettingsService.TemplateUpdateCommand> commands = request.templates().stream()
                .map(item -> new ConfluenceTemplateSettingsService.TemplateUpdateCommand(item.id(), item.text()))
                .collect(Collectors.toList());
        return settingsService.updateTemplates(commands).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private ConfluenceTemplateDto toDto(ConfluenceTemplate template) {
        return new ConfluenceTemplateDto(template.id(), template.name(), template.testType(), template.text());
    }
}
