package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record KubernetesServiceListResponse(@JsonProperty("items") List<KubernetesService> items) {}
