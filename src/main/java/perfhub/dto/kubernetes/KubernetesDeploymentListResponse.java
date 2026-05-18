package com.example.chronicler.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Список Deployment, возвращаемый Kubernetes API. */
public record KubernetesDeploymentListResponse(@JsonProperty("items") List<KubernetesDeployment> items) {}
