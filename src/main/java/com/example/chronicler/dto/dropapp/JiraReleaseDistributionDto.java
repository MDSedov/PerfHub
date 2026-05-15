package com.example.chronicler.dto.dropapp;

/** Дистрибутив из инфраструктурной задачи Jira, связанный со снимком конфигурации. */
public record JiraReleaseDistributionDto(
        String serviceName,
        String distributionName,
        String distributionUrl) {}
