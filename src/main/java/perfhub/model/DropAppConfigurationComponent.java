package com.example.chronicler.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.util.StringUtils;

public enum DropAppConfigurationComponent {
    PODS("pods", "Pods", "POD и контейнеры с версиями и лимитами ресурсов"),
    DEPLOYMENTS("deployments", "Deployments", "Deployment с версиями и релизными метками"),
    CONFIGMAPS("configmaps", "ConfigMaps", "ConfigMap и количество ключей"),
    SERVICES("services", "Services", "Сервисы Kubernetes, их тип и ClusterIP"),
    INGRESS("ingress", "Ingress", "Ingress с ingress class и хостами");

    private final String code;
    private final String label;
    private final String description;

    DropAppConfigurationComponent(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static List<DropAppConfigurationComponent> defaultSelection() {
        return List.of(values());
    }

    public static List<DropAppConfigurationComponent> resolve(
            List<String> rawComponents,
            List<DropAppConfigurationComponent> fallback) {
        if (rawComponents == null || rawComponents.isEmpty()) {
            return fallback == null || fallback.isEmpty() ? defaultSelection() : List.copyOf(fallback);
        }
        List<DropAppConfigurationComponent> resolved = rawComponents.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(DropAppConfigurationComponent::fromCode)
                .distinct()
                .toList();
        return resolved.isEmpty()
                ? (fallback == null || fallback.isEmpty() ? defaultSelection() : List.copyOf(fallback))
                : resolved;
    }

    public static DropAppConfigurationComponent fromCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Код компонента DropApp не должен быть пустым");
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(component -> component.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Компонент DropApp '%s' не поддерживается".formatted(code)));
    }
}
