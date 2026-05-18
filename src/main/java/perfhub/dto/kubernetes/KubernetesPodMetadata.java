package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Метаданные Pod. */
public record KubernetesPodMetadata(
        @JsonProperty("name") String name, @JsonProperty("labels") Map<String, String> labels) {}
