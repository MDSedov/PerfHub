package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Список Pod, возвращаемый Kubernetes API. */
public record KubernetesPodListResponse(@JsonProperty("items") List<KubernetesPod> items) {}
