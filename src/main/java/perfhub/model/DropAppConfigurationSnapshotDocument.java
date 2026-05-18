package com.example.chronicler.model;

/** YAML-документ ресурса Kubernetes, сохраненный внутри снапшота конфигурации DropApp. */
public record DropAppConfigurationSnapshotDocument(
        Long id,
        Long snapshotId,
        String namespace,
        String componentCode,
        String resourceName,
        String content) {}
