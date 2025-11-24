package com.example.chronicler.dto.dropapp;

/** Элемент конфигурации DropApp, подготовленный для фронтенда. */
public record DropAppConfigurationItemDto(
        String namespace,
        String serviceName,
        String podName,
        String containerName,
        String version,
        String requestCpu,
        String limitCpu,
        String requestRam,
        String limitRam) {}
