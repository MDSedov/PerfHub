package com.example.chronicler.service;

import com.example.chronicler.client.DropAppClient;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.exception.DropAppClientException;
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

    /** Выполняет легкий запрос к DropApp, чтобы токен не простаивал. */
    public void keepAlive() {
        dropAppClient.fetchNamespaces();
    }

    /** Проверяет, что для фонового запроса DropApp настроены URL и токен. */
    public boolean isConfigured() {
        return hasTextValue(BASE_URL_KEY) && hasTextValue(TOKEN_KEY);
    }

    /** Возвращает актуальную конфигурацию DropApp для namespace, перечисленных в настройках. */
    public List<DropAppPodConfiguration> loadCurrentConfiguration() {
        return dropAppClient.fetchConfiguration(resolveNamespaces(null));
    }

    /** Возвращает конфигурацию DropApp для указанного списка namespace. */
    public List<DropAppPodConfiguration> loadConfiguration(List<String> namespaces) {
        return dropAppClient.fetchConfiguration(resolveNamespaces(namespaces));
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
        return Arrays.stream(rawNamespaces.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }
}
