package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record KubernetesConfigMapListResponse(@JsonProperty("items") List<KubernetesConfigMap> items) {}
