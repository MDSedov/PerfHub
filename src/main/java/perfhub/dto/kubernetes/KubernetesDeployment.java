package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Deployment из Kubernetes API. */
public record KubernetesDeployment(
        @JsonProperty("metadata") KubernetesPodMetadata metadata,
        @JsonProperty("spec") KubernetesDeploymentSpec spec) {}
