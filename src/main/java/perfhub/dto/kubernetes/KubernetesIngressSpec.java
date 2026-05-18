package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record KubernetesIngressSpec(
        @JsonProperty("ingressClassName") String ingressClassName,
        @JsonProperty("rules") List<KubernetesIngressRule> rules) {}
