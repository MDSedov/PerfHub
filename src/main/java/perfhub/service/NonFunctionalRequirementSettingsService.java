package com.example.chronicler.service;

import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.repository.NonFunctionalRequirementRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NonFunctionalRequirementSettingsService {

    private final NonFunctionalRequirementRepository repository;

    public NonFunctionalRequirementSettingsService(NonFunctionalRequirementRepository repository) {
        this.repository = repository;
    }

    public List<NonFunctionalRequirement> listAll() {
        return repository.findAll();
    }

    public NonFunctionalRequirement create(NonFunctionalRequirement requirement) {
        NonFunctionalRequirement sanitized = sanitize(requirement, false);
        return repository.insert(sanitized);
    }

    public NonFunctionalRequirement update(NonFunctionalRequirement requirement) {
        NonFunctionalRequirement sanitized = sanitize(requirement, true);
        return repository.update(sanitized);
    }

    public void delete(int id) {
        repository.delete(id);
    }

    private NonFunctionalRequirement sanitize(NonFunctionalRequirement requirement, boolean requireId) {
        if (requirement == null) {
            throw new IllegalArgumentException("Requirement payload must not be null");
        }
        if (requireId && requirement.id() <= 0) {
            throw new IllegalArgumentException("Requirement id must be a positive integer");
        }
        if (requirement.metricType() == null) {
            throw new IllegalArgumentException("Requirement metric type must be provided");
        }
        String code = StringUtils.trimWhitespace(requirement.code());
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Requirement code must not be blank");
        }
        String thresholdUnit = StringUtils.trimWhitespace(requirement.thresholdUnit());
        if (!StringUtils.hasText(thresholdUnit)) {
            throw new IllegalArgumentException("Requirement threshold unit must not be blank");
        }
        double thresholdValue = requirement.thresholdValue();
        if (Double.isNaN(thresholdValue) || Double.isInfinite(thresholdValue)) {
            throw new IllegalArgumentException("Requirement threshold value must be a finite number");
        }
        JsonNode parameters = requirement.parameters();
        if (parameters == null || parameters.isNull()) {
            parameters = JsonNodeFactory.instance.objectNode();
        }
        String descriptionRu = normalizeDescription(requirement.descriptionRu());
        return new NonFunctionalRequirement(
                requirement.id(),
                code,
                requirement.metricType(),
                thresholdValue,
                thresholdUnit,
                requirement.active(),
                parameters,
                descriptionRu);
    }

    private String normalizeDescription(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
