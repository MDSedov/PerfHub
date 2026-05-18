package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KubernetesServiceSpec(
        @JsonProperty("type") String type,
        @JsonProperty("clusterIP") String clusterIp) {}
