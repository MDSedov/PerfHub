package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Селектор Deployment. */
public record KubernetesDeploymentSelector(@JsonProperty("matchLabels") Map<String, String> matchLabels) {}
