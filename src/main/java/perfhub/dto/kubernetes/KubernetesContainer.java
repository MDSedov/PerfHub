package perfhub.dto.kubernetes;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Контейнер внутри Pod с данными о ресурсах. */
public record KubernetesContainer(
        @JsonProperty("name") String name,
        @JsonProperty("image") String image,
        @JsonProperty("resources") KubernetesResourceRequirements resources) {

    public KubernetesContainer(String name, KubernetesResourceRequirements resources) {
        this(name, null, resources);
    }

    public String requestCpu() {
        return get(resources != null ? resources.requests() : null, "cpu");
    }

    public String limitCpu() {
        return get(resources != null ? resources.limits() : null, "cpu");
    }

    public String requestRam() {
        return get(resources != null ? resources.requests() : null, "memory");
    }

    public String limitRam() {
        return get(resources != null ? resources.limits() : null, "memory");
    }

    private String get(java.util.Map<String, String> values, String key) {
        if (values == null) {
            return null;
        }
        return values.getOrDefault(key, null);
    }
}
