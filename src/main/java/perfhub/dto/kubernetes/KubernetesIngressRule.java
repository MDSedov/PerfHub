package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KubernetesIngressRule(@JsonProperty("host") String host) {}
