package com.example.chronicler.service;

import com.example.chronicler.model.ConfigurationEntry;
import com.example.chronicler.repository.ConfigurationRepository;
import com.example.chronicler.security.CurrentUserProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfigurationService implements ConfigurationProvider {

    private final ConfigurationRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public ConfigurationService(
            ConfigurationRepository repository,
            CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    public List<ConfigurationEntry> fetchAll() {
        return repository.findAllOrdered(currentUserIdOrNull());
    }

    public void upsertAll(List<ConfigurationEntry> entries) {
        Long userId = currentUserIdOrNull();
        for (ConfigurationEntry entry : entries) {
            String service = StringUtils.trimWhitespace(entry.service());
            String key = StringUtils.trimWhitespace(entry.key());
            if (!StringUtils.hasText(service)) {
                throw new IllegalArgumentException("Service name must not be blank");
            }
            if (!StringUtils.hasText(key)) {
                throw new IllegalArgumentException("Configuration key must not be blank");
            }
            String value = entry.value() != null ? entry.value() : "";
            repository.upsert(service, key, value, userId);
        }
    }

    @Override
    public String getRequiredValue(String service, String key) {
        String value = findValue(service, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Configuration parameter not found for service '%s' and key '%s'"
                                .formatted(service, key)));
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "Не задан обязательный параметр подключения %s.%s".formatted(service, key));
        }
        return value;
    }

    @Override
    public Optional<String> findValue(String service, String key) {
        return repository.findValue(service, key, currentUserIdOrNull());
    }

    private Long currentUserIdOrNull() {
        return currentUserProvider.currentUserId()
                .map(userId -> {
                    repository.copyGlobalConnectionValuesToUser(userId);
                    return userId;
                })
                .orElse(null);
    }
}
