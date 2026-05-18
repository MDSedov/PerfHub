package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Спецификация Deployment. */
public record KubernetesDeploymentSpec(@JsonProperty("selector") KubernetesDeploymentSelector selector) {}
