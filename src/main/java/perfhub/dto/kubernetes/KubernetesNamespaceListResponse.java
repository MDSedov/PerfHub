package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KubernetesNamespaceListResponse(List<KubernetesNamespaceMetadata> items) {
}
