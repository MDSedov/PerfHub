package perfhub.dto.dropapp;

/** Дистрибутив из инфраструктурной задачи Jira, связанный со снимком конфигурации. */
public record JiraReleaseDistributionDto(
        String serviceName,
        String serviceUrl,
        String distributionName,
        String distributionUrl) {}
