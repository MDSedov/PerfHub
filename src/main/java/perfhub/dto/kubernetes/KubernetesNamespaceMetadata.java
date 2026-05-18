package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KubernetesNamespaceMetadata(String name) {
}
