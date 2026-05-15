package com.example.chronicler.dto.jira;

public record JiraReleaseDistribution(
        String serviceName,
        String distributionName,
        String distributionUrl) {}
