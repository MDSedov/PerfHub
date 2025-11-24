package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Метаданные Pod. */
public record KubernetesPodMetadata(@JsonProperty("name") String name) {}
