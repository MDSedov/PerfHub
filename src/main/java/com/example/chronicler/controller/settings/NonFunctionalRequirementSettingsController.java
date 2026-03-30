package com.example.chronicler.controller.settings;

import com.example.chronicler.dto.settings.NonFunctionalRequirementDto;
import com.example.chronicler.dto.settings.NonFunctionalRequirementRequest;
import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.model.RequirementMetricType;
import com.example.chronicler.service.NonFunctionalRequirementSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(
        path = "/api/settings/non-functional-requirements",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class NonFunctionalRequirementSettingsController {

    private final NonFunctionalRequirementSettingsService service;

    public NonFunctionalRequirementSettingsController(NonFunctionalRequirementSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<NonFunctionalRequirementDto> listRequirements() {
        return service.listAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public NonFunctionalRequirementDto createRequirement(
            @Valid @RequestBody NonFunctionalRequirementRequest request) {
        NonFunctionalRequirement created = service.create(toModel(request, 0));
        return toDto(created);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NonFunctionalRequirementDto updateRequirement(
            @PathVariable("id") int id,
            @Valid @RequestBody NonFunctionalRequirementRequest request) {
        NonFunctionalRequirement updated = service.update(toModel(request, id));
        return toDto(updated);
    }

    @DeleteMapping("/{id}")
    public void deleteRequirement(@PathVariable("id") int id) {
        service.delete(id);
    }

    private NonFunctionalRequirementDto toDto(NonFunctionalRequirement requirement) {
        return new NonFunctionalRequirementDto(
                requirement.id(),
                requirement.code(),
                requirement.metricType().name(),
                requirement.thresholdValue(),
                requirement.thresholdUnit(),
                requirement.active(),
                requirement.parameters(),
                requirement.descriptionRu());
    }

    private NonFunctionalRequirement toModel(NonFunctionalRequirementRequest request, int id) {
        RequirementMetricType metricType = parseMetricType(request.metricType());
        JsonNode parameters = request.parameters() == null
                ? JsonNodeFactory.instance.objectNode()
                : request.parameters();
        Double thresholdValue = request.thresholdValue();
        if (thresholdValue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Threshold value is required");
        }
        return new NonFunctionalRequirement(
                id,
                request.code(),
                metricType,
                thresholdValue,
                request.thresholdUnit(),
                Boolean.TRUE.equals(request.active()),
                parameters,
                request.descriptionRu());
    }

    private RequirementMetricType parseMetricType(String rawMetricType) {
        try {
            return RequirementMetricType.valueOf(rawMetricType.trim().toUpperCase());
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown metric type: " + rawMetricType, ex);
        }
    }
}
