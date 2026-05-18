package perfhub.model;

/** Дистрибутив из Jira, сохраненный вместе со снимком конфигурации DropApp. */
public record DropAppConfigurationSnapshotDistribution(
        Long id,
        Long snapshotId,
        String serviceName,
        String serviceUrl,
        String distributionName,
        String distributionUrl) {}
