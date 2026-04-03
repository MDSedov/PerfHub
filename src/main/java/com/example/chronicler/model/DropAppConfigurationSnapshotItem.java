package com.example.chronicler.model;

/** Элемент сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotItem(
        Long id,
        Long snapshotId,
        String namespace,
        String serviceName,
        String podName,
        String containerName,
        String version,
        String release,
        String requestCpu,
        String limitCpu,
        String requestRam,
        String limitRam) {}
