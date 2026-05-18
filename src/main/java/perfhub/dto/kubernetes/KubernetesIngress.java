package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KubernetesIngress(
        @JsonProperty("metadata") KubernetesPodMetadata metadata,
        @JsonProperty("spec") KubernetesIngressSpec spec) {}
