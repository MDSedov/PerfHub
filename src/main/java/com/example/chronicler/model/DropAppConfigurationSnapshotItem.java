package com.example.chronicler.model;

/** Элемент сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotItem(
        Long id,
        Long snapshotId,
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
        String limitRam) {

    public DropAppConfigurationSnapshotItem(
            Long id,
            Long snapshotId,
            String namespace,
            String serviceName,
            String podName,
            String containerName,
            String version,
            String release,
            String requestCpu,
            String limitCpu,
            String requestRam,
            String limitRam) {
        this(
                id,
                snapshotId,
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
                limitRam);
    }
}
