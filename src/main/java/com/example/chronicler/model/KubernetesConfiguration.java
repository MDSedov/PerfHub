package com.example.chronicler.model;

import java.time.Instant;
import java.util.UUID;

/** Записанная конфигурация Kubernetes для конкретного нагрузочного теста. */
public record KubernetesConfiguration(
        Long id,
        UUID testUuid,
        String namespace,
        String serviceName,
        String podName,
        String containerName,
        String version,
        String release,
        String requestCpu,
        String limitCpu,
        String requestRam,
        String limitRam,
        Instant capturedAt) {}
