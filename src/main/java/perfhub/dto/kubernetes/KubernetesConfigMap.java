package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record KubernetesConfigMap(
        @JsonProperty("metadata") KubernetesPodMetadata metadata,
        @JsonProperty("data") Map<String, String> data) {}
