package com.example.chronicler.service;

import com.example.chronicler.client.DropAppClient;
import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.dto.dropapp.DropAppServiceOptionDto;
import com.example.chronicler.dto.dropapp.DropAppServiceSelectionDto;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.util.DropAppConfigurationUtils;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** Возвращает конфигурацию DropApp, отфильтрованную по выбранным сервисам стенда. */
    public List<DropAppPodConfiguration> loadConfiguration(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components,
            List<DropAppServiceSelectionDto> services) {
        List<String> resolvedNamespaces = resolveNamespaces(namespaces);
        List<DropAppConfigurationComponent> resolvedComponents = components == null || components.isEmpty()
                ? List.of(DropAppConfigurationComponent.PODS)
                : components.stream().distinct().toList();
        return filterConfigurationByServices(
                groupPodItems(dropAppClient.fetchConfiguration(resolvedNamespaces, resolvedComponents)),
                services,
                resolvedNamespaces);
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

    /** Возвращает YAML-документы, отфильтрованные по выбранным сервисам стенда. */
    public List<DropAppConfigurationSnapshotDocument> loadConfigurationDocuments(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components,
            List<DropAppServiceSelectionDto> services) {
        List<String> resolvedNamespaces = resolveNamespaces(namespaces);
        return filterDocumentsByServices(loadConfigurationDocuments(resolvedNamespaces, components), services, resolvedNamespaces);
    }

    /** Возвращает сервисы, доступные для выгрузки в выбранных namespace. */
    public List<DropAppServiceOptionDto> listServiceOptions(List<String> namespaces) {
        List<String> resolvedNamespaces = resolveNamespaces(namespaces);
        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(
                dropAppClient.fetchConfiguration(resolvedNamespaces, List.of(DropAppConfigurationComponent.PODS)).stream()
                        .map(this::toDto)
                        .toList());

        Map<ServiceSelection, DropAppServiceOptionDto> services = new LinkedHashMap<>();
        for (DropAppConfigurationItemDto item : items) {
            if (!DropAppConfigurationUtils.isPodResourceItem(item)) {
                continue;
            }
            String namespace = normalizeText(item.namespace());
            String serviceName = DropAppConfigurationUtils.resolveServiceName(item.serviceName(), item.podName());
            if (!StringUtils.hasText(namespace) || !StringUtils.hasText(serviceName)) {
                continue;
            }
            DropAppServiceOptionDto option = new DropAppServiceOptionDto(
                    namespace,
                    serviceName,
                    item.podCount(),
                    item.release());
            services.merge(toServiceSelection(namespace, serviceName), option, this::mergeServiceOptions);
        }
        return List.copyOf(services.values());
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

    private List<DropAppPodConfiguration> filterConfigurationByServices(
            List<DropAppPodConfiguration> items,
            List<DropAppServiceSelectionDto> services,
            List<String> fallbackNamespaces) {
        Set<ServiceSelection> selectedServices = normalizeServiceSelections(services, fallbackNamespaces);
        if (selectedServices.isEmpty() || items == null || items.isEmpty()) {
            return items == null ? List.of() : items;
        }
        return items.stream()
                .filter(item -> matchesSelectedService(
                        item.namespace(),
                        DropAppConfigurationUtils.isPodResourceItem(toDto(item))
                                ? DropAppConfigurationUtils.resolveServiceName(item.serviceName(), item.podName())
                                : item.podName(),
                        selectedServices))
                .toList();
    }

    private List<DropAppConfigurationSnapshotDocument> filterDocumentsByServices(
            List<DropAppConfigurationSnapshotDocument> documents,
            List<DropAppServiceSelectionDto> services,
            List<String> fallbackNamespaces) {
        Set<ServiceSelection> selectedServices = normalizeServiceSelections(services, fallbackNamespaces);
        if (selectedServices.isEmpty() || documents == null || documents.isEmpty()) {
            return documents == null ? List.of() : documents;
        }
        return documents.stream()
                .filter(document -> matchesSelectedService(document.namespace(), document.resourceName(), selectedServices))
                .toList();
    }

    private Set<ServiceSelection> normalizeServiceSelections(
            List<DropAppServiceSelectionDto> services,
            List<String> fallbackNamespaces) {
        if (services == null || services.isEmpty()) {
            return Set.of();
        }
        List<String> namespaces = normalizeNamespaces(fallbackNamespaces);
        Set<ServiceSelection> result = new LinkedHashSet<>();
        for (DropAppServiceSelectionDto service : services) {
            if (service == null || !StringUtils.hasText(service.serviceName())) {
                continue;
            }
            String namespace = normalizeText(service.namespace());
            if (namespace != null) {
                result.add(toServiceSelection(namespace, service.serviceName()));
                continue;
            }
            namespaces.stream()
                    .map(fallbackNamespace -> toServiceSelection(fallbackNamespace, service.serviceName()))
                    .forEach(result::add);
        }
        return result;
    }

    private boolean matchesSelectedService(
            String namespace,
            String resourceName,
            Set<ServiceSelection> selectedServices) {
        String normalizedNamespace = normalizeLookupText(namespace);
        String normalizedResourceName = normalizeLookupText(resourceName);
        if (normalizedNamespace == null || normalizedResourceName == null) {
            return false;
        }
        return selectedServices.stream()
                .anyMatch(selection -> Objects.equals(selection.namespace(), normalizedNamespace)
                        && matchesServiceResourceName(normalizedResourceName, selection.serviceName()));
    }

    private boolean matchesServiceResourceName(String resourceName, String serviceName) {
        return Objects.equals(resourceName, serviceName)
                || resourceName.startsWith(serviceName + "-")
                || resourceName.startsWith(serviceName + ".")
                || resourceName.startsWith(serviceName + "_");
    }

    private DropAppServiceOptionDto mergeServiceOptions(
            DropAppServiceOptionDto left,
            DropAppServiceOptionDto right) {
        List<String> releases = new ArrayList<>(2);
        releases.add(left.release());
        releases.add(right.release());
        String release = ReleaseVersionUtils.findMaxRelease(releases);
        int leftPodCount = left.podCount() != null ? left.podCount() : 0;
        int rightPodCount = right.podCount() != null ? right.podCount() : 0;
        Integer podCount = Math.max(leftPodCount, rightPodCount);
        return new DropAppServiceOptionDto(
                left.namespace(),
                left.serviceName(),
                podCount > 0 ? podCount : null,
                release);
    }

    private ServiceSelection toServiceSelection(String namespace, String serviceName) {
        return new ServiceSelection(normalizeLookupText(namespace), normalizeLookupText(serviceName));
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeLookupText(String value) {
        String normalized = normalizeText(value);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : null;
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

    private record ServiceSelection(String namespace, String serviceName) {}
}
