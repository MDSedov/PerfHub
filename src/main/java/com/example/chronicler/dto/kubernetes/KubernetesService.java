package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KubernetesService(
        @JsonProperty("metadata") KubernetesPodMetadata metadata,
        @JsonProperty("spec") KubernetesServiceSpec spec) {}
