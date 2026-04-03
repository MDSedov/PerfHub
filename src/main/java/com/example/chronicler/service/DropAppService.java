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
        String rawNamespaces = configurationProvider.getRequiredValue(SERVICE, NAMESPACES_KEY);
        List<String> namespaces = Arrays.stream(rawNamespaces.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        if (namespaces.isEmpty()) {
            throw new DropAppClientException("В конфигурации DropApp не задан ни один namespace (dropapp.namespaces)");
        }
        return dropAppClient.fetchConfiguration(namespaces);
    }

    /** Возвращает конфигурацию DropApp для указанного списка namespace. */
    public List<DropAppPodConfiguration> loadConfiguration(List<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return loadCurrentConfiguration();
        }
        return dropAppClient.fetchConfiguration(namespaces);
    }

    private boolean hasTextValue(String key) {
        return configurationProvider.findValue(SERVICE, key)
                .map(StringUtils::hasText)
                .orElse(false);
    }
}
