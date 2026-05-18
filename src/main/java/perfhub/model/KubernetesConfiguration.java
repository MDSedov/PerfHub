package perfhub.model;

import java.time.Instant;
import java.util.UUID;

/** Записанная конфигурация Kubernetes для конкретного нагрузочного теста. */
public record KubernetesConfiguration(
        Long id,
        UUID testUuid,
        String namespace,
        String serviceName,
        String podName,
        Integer podCount,
        String containerName,
        String version,
        String release,
        String requestCpu,
        String limitCpu,
        String requestRam,
        String limitRam,
        Instant capturedAt) {

    public KubernetesConfiguration(
            Long id,
            UUID testUuid,
            String namespace,
            String serviceName,
            String podName,
            String containerName,
            String version,
            String release,
            String requestCpu,
            String limitCpu,
            String requestRam,
            String limitRam,
            Instant capturedAt) {
        this(
                id,
                testUuid,
                namespace,
                serviceName,
                podName,
                null,
                containerName,
                version,
                release,
                requestCpu,
                limitCpu,
                requestRam,
                limitRam,
                capturedAt);
    }
}
