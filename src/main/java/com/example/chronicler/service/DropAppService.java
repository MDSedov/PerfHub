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

    private final DropAppClient dropAppClient;
    private final ConfigurationProvider configurationProvider;

    public DropAppService(DropAppClient dropAppClient, ConfigurationProvider configurationProvider) {
        this.dropAppClient = dropAppClient;
        this.configurationProvider = configurationProvider;
    }

    /** Возвращает актуальную конфигурацию DropApp для namespace, перечисленных в настройках. */
    public List<DropAppPodConfiguration> loadCurrentConfiguration() {
        String rawNamespaces = configurationProvider.getRequiredValue("dropapp", "namespaces");
        List<String> namespaces = Arrays.stream(rawNamespaces.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        if (namespaces.isEmpty()) {
            throw new DropAppClientException("В конфигурации DropApp не задан ни один namespace (dropapp.namespaces)");
        }
        return dropAppClient.fetchConfiguration(namespaces);
    }
}
