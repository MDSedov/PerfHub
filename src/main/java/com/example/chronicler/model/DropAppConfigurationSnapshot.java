package com.example.chronicler.model;

import java.time.Instant;

/** Заголовок сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshot(Long id, String release, String comment, String namespaces, Instant savedAt) {}
