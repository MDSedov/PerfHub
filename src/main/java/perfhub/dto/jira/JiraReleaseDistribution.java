package perfhub.dto.jira;

public record JiraReleaseDistribution(
        String serviceName,
        String serviceUrl,
        String distributionName,
        String distributionUrl) {}
