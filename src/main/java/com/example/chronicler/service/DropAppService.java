package com.example.chronicler.service;

import com.example.chronicler.client.DropAppClient;
import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.util.DropAppConfigurationUtils;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Сервис для работы с конфигурацией DropApp. */
@Service
public class DropAppService {

    private static final String SERVICE = "dropapp";
    private static final String BASE_URL_KEY = "base-url";
    private static final String TOKEN_KEY = "token";
    private static final String NAMESPACES_KEY = "namespaces";

    private final DropAppClient dropAppClient;
    private final ConfigurationProvider configurationProvider;

    public DropAppService(DropAppClient dropAppClient, ConfigurationProvider configurationProvider) {
        this.dropAppClient = dropAppClient;
        this.configurationProvider = configurationProvider;
    }

    /** Возвращает список доступных namespace из DropApp. */
    public List<String> listNamespaces() {
        return dropAppClient.fetchNamespaces();
    }

    /** Возвращает список namespace, заданных в настройках приложения. */
    public List<String> getConfiguredNamespaces() {
        return configurationProvider.findValue(SERVICE, NAMESPACES_KEY)
                .map(this::parseNamespaces)
                .orElse(List.of());
    }

    /** Проверяет, что для обращения к DropApp настроены URL и токен. */
    public boolean isConfigured() {
        return hasTextValue(BASE_URL_KEY) && hasTextValue(TOKEN_KEY);
    }

    /** Возвращает актуальную конфигурацию DropApp для namespace, перечисленных в настройках. */
    public List<DropAppPodConfiguration> loadCurrentConfiguration() {
        return groupPodItems(dropAppClient.fetchConfiguration(resolveNamespaces(null)));
    }

    /** Возвращает конфигурацию DropApp для указанного списка namespace. */
    public List<DropAppPodConfiguration> loadConfiguration(List<String> namespaces) {
        return groupPodItems(dropAppClient.fetchConfiguration(resolveNamespaces(namespaces)));
    }

    /** Возвращает конфигурацию DropApp для указанного списка namespace и типов ресурсов. */
    public List<DropAppPodConfiguration> loadConfiguration(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components) {
        List<DropAppConfigurationComponent> resolvedComponents = components == null || components.isEmpty()
                ? List.of(DropAppConfigurationComponent.PODS)
                : components.stream().distinct().toList();
        return groupPodItems(dropAppClient.fetchConfiguration(resolveNamespaces(namespaces), resolvedComponents));
    }

    /** Возвращает YAML-документы выбранных ресурсов Kubernetes для указанных namespace. */
    public List<DropAppConfigurationSnapshotDocument> loadConfigurationDocuments(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components) {
        List<DropAppConfigurationComponent> resolvedComponents = components == null || components.isEmpty()
                ? List.of()
                : components.stream().distinct().toList();
        if (resolvedComponents.isEmpty()) {
            return List.of();
        }
        return dropAppClient.fetchConfigurationDocuments(resolveNamespaces(namespaces), resolvedComponents);
    }

    /** Возвращает итоговый список namespace: выбранные пользователем или namespace из настроек. */
    public List<String> resolveNamespaces(List<String> namespaces) {
        List<String> normalizedNamespaces = normalizeNamespaces(namespaces);
        if (!normalizedNamespaces.isEmpty()) {
            return normalizedNamespaces;
        }
        List<String> configuredNamespaces = getConfiguredNamespaces();
        if (!configuredNamespaces.isEmpty()) {
            return configuredNamespaces;
        }
        throw new DropAppClientException("В конфигурации DropApp не задан ни один namespace (dropapp.namespaces)");
    }

    private boolean hasTextValue(String key) {
        return configurationProvider.findValue(SERVICE, key)
                .map(StringUtils::hasText)
                .orElse(false);
    }

    private List<String> normalizeNamespaces(List<String> namespaces) {
        if (namespaces == null) {
            return List.of();
        }
        return namespaces.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> parseNamespaces(String rawNamespaces) {
        if (!StringUtils.hasText(rawNamespaces)) {
            return List.of();
        }
        return Arrays.stream(rawNamespaces.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<DropAppPodConfiguration> groupPodItems(List<DropAppPodConfiguration> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return DropAppConfigurationUtils.groupPodItems(items.stream()
                        .map(this::toDto)
                        .toList())
                .stream()
                .map(this::toDropAppConfiguration)
                .toList();
    }

    private DropAppConfigurationItemDto toDto(DropAppPodConfiguration item) {
        return new DropAppConfigurationItemDto(
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.podCount(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }

    private DropAppPodConfiguration toDropAppConfiguration(DropAppConfigurationItemDto item) {
        return new DropAppPodConfiguration(
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.podCount(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }
}
