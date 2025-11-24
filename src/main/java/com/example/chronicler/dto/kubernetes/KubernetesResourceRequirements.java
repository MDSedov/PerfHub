package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Запрошенные и лимитные ресурсы контейнера. */
public record KubernetesResourceRequirements(
        @JsonProperty("requests") Map<String, String> requests, @JsonProperty("limits") Map<String, String> limits) {}
