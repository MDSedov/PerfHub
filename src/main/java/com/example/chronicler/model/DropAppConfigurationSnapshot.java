package com.example.chronicler.model;

import java.time.Instant;
import java.util.UUID;

/** Заголовок сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshot(
        Long id,
        UUID testUuid,
        String release,
        String comment,
        String namespaces,
        String components,
        Instant savedAt) {}
