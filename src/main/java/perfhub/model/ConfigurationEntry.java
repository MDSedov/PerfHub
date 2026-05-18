package com.example.chronicler.model;

public record ConfigurationEntry(
        Long id, String service, String key, String value, String description, Integer sortOrder) {
}
