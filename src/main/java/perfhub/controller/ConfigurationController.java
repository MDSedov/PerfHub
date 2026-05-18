package com.example.chronicler.controller;

import com.example.chronicler.dto.configuration.ConfigurationItemDto;
import com.example.chronicler.dto.configuration.ConfigurationUpdateRequest;
import com.example.chronicler.model.ConfigurationEntry;
import com.example.chronicler.service.ConfigurationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/chronicler/properties/", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfigurationController {

    private final ConfigurationService configurationService;

    public ConfigurationController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @GetMapping
    public List<ConfigurationItemDto> listAll() {
        return configurationService.fetchAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<ConfigurationItemDto> update(@Valid @RequestBody ConfigurationUpdateRequest request) {
        List<ConfigurationEntry> entries = request.parameters().stream()
                .map(item -> new ConfigurationEntry(
                        null,
                        item.service().trim(),
                        item.key().trim(),
                        item.value(),
                        null,
                        null))
                .collect(Collectors.toList());
        configurationService.upsertAll(entries);
        return listAll();
    }

    private ConfigurationItemDto toDto(ConfigurationEntry entry) {
        return new ConfigurationItemDto(
                entry.id(), entry.service(), entry.key(), entry.value(), entry.description(), entry.sortOrder());
    }
}
