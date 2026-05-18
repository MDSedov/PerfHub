package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Спецификация Pod. */
public record KubernetesPodSpec(@JsonProperty("containers") List<KubernetesContainer> containers) {}
