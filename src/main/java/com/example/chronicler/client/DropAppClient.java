package com.example.chronicler.client;

import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.dto.kubernetes.KubernetesContainer;
import com.example.chronicler.dto.kubernetes.KubernetesDeployment;
import com.example.chronicler.dto.kubernetes.KubernetesDeploymentListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesPod;
import com.example.chronicler.dto.kubernetes.KubernetesPodListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesPodMetadata;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.function.Function;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Клиент для обращения к DropApp. */
@Component
public class DropAppClient {

    private static final Logger log = LoggerFactory.getLogger(DropAppClient.class);

    private static final String SERVICE = "dropapp";
    private static final String TOKEN_KEY = "token";
    private static final String BASE_URL_KEY = "base-url";
    private static final Set<String> SENSITIVE_HEADERS = Set.of(HttpHeaders.AUTHORIZATION);

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public DropAppClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    /** Получает конфигурацию ресурсов Pod/Container из Kubernetes API для указанных namespace. */
    public List<DropAppPodConfiguration> fetchConfiguration(List<String> namespaces) {
        Assert.notEmpty(namespaces, "Список namespace DropApp не должен быть пустым");
        HttpMethod method = HttpMethod.GET;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(configurationProvider.getRequiredValue(SERVICE, TOKEN_KEY));

        return namespaces.stream()
                .flatMap(namespace -> fetchNamespacePods(namespace, method, headers).stream())
                .collect(Collectors.toList());
    }

    /** Формирует полный URI запроса списка Pod для namespace. */
    private URI buildPodsUri(String namespace) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/v1/namespaces/{namespace}/pods")
                    .buildAndExpand(namespace)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

    /** Формирует полный URI запроса списка Deployment для namespace. */
    private URI buildDeploymentsUri(String namespace) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/apis/apps/v1/namespaces/{namespace}/deployments")
                    .buildAndExpand(namespace)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

    private List<DropAppPodConfiguration> fetchNamespacePods(String namespace, HttpMethod method, HttpHeaders headers) {
        List<DeploymentDescriptor> deployments = fetchDeploymentDescriptors(namespace, method, headers);
        URI requestUri = buildPodsUri(namespace);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        if (log.isDebugEnabled()) {
            log.debug("Запрос pod-ов Kubernetes по адресу {} для namespace {}", requestUri, namespace);
            log.debug("Kubernetes request details: {} {} with headers {}", method, requestUri, headersForLogging(headers));
        }
        try {
            ResponseEntity<KubernetesPodListResponse> response = restTemplate.exchange(
                    requestUri, method, request, KubernetesPodListResponse.class);
            KubernetesPodListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            List<DropAppPodConfiguration> result = new ArrayList<>();
            for (KubernetesPod pod : body.items()) {
                KubernetesPodMetadata metadata = pod.metadata();
                String podName = metadata != null ? metadata.name() : null;
                Map<String, String> podLabels = metadata != null && metadata.labels() != null
                        ? metadata.labels()
                        : Collections.emptyMap();
                String version = resolveVersion(podLabels, deployments);
                String release = extractRelease(podLabels);
                if (pod.spec() == null || pod.spec().containers() == null) {
                    continue;
                }
                for (KubernetesContainer container : pod.spec().containers()) {
                    result.add(new DropAppPodConfiguration(
                            namespace,
                            null,
                            podName,
                            container.name(),
                            version,
                            release,
                            extractValue(container, KubernetesContainer::requestCpu),
                            extractValue(container, KubernetesContainer::limitCpu),
                            extractValue(container, KubernetesContainer::requestRam),
                            extractValue(container, KubernetesContainer::limitRam)));
                }
            }
            return result;
        } catch (RestClientResponseException ex) {
            log.error(
                    "Запрос к Kubernetes API завершился ошибкой {} {}. Запрос: {} {}. Заголовки запроса: {}. "
                            + "Заголовки ответа: {}. Тело ответа: {}",
                    ex.getRawStatusCode(),
                    ex.getStatusText(),
                    method,
                    requestUri,
                    headersForLogging(headers),
                    headersForLogging(ex.getResponseHeaders()),
                    ex.getResponseBodyAsString());
            throw new DropAppClientException(
                    "Не удалось получить конфигурацию Kubernetes: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            log.error(
                    "Ошибка HTTP-клиента при обращении к Kubernetes API. Запрос: {} {}. Заголовки запроса: {}",
                    method,
                    requestUri,
                    headersForLogging(headers),
                    ex);
            throw new DropAppClientException("Не удалось получить конфигурацию Kubernetes: " + ex.getMessage(), ex);
        }
    }

    private List<DeploymentDescriptor> fetchDeploymentDescriptors(
            String namespace, HttpMethod method, HttpHeaders headers) {
        URI requestUri = buildDeploymentsUri(namespace);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        if (log.isDebugEnabled()) {
            log.debug("Запрос deployments Kubernetes по адресу {} для namespace {}", requestUri, namespace);
            log.debug("Kubernetes request details: {} {} with headers {}", method, requestUri, headersForLogging(headers));
        }
        try {
            ResponseEntity<KubernetesDeploymentListResponse> response = restTemplate.exchange(
                    requestUri, method, request, KubernetesDeploymentListResponse.class);
            KubernetesDeploymentListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            List<DeploymentDescriptor> descriptors = new ArrayList<>();
            for (KubernetesDeployment deployment : body.items()) {
                KubernetesPodMetadata metadata = deployment.metadata();
                String version = extractVersion(metadata);
                Map<String, String> selector = extractSelectorLabels(deployment);
                if (selector.isEmpty()) {
                    continue;
                }
                descriptors.add(new DeploymentDescriptor(selector, version));
            }
            return descriptors;
        } catch (RestClientResponseException ex) {
            log.error(
                    "Запрос к Kubernetes API (deployments) завершился ошибкой {} {}. Запрос: {} {}. Заголовки запроса: {}. "
                            + "Заголовки ответа: {}. Тело ответа: {}",
                    ex.getRawStatusCode(),
                    ex.getStatusText(),
                    method,
                    requestUri,
                    headersForLogging(headers),
                    headersForLogging(ex.getResponseHeaders()),
                    ex.getResponseBodyAsString());
            throw new DropAppClientException(
                    "Не удалось получить список deployments Kubernetes: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            log.error(
                    "Ошибка HTTP-клиента при обращении к Kubernetes API (deployments). Запрос: {} {}. Заголовки запроса: {}",
                    method,
                    requestUri,
                    headersForLogging(headers),
                    ex);
            throw new DropAppClientException("Не удалось получить список deployments Kubernetes: " + ex.getMessage(), ex);
        }
    }

    private String resolveVersion(Map<String, String> podLabels, List<DeploymentDescriptor> deployments) {
        if (podLabels == null || podLabels.isEmpty() || deployments.isEmpty()) {
            return "";
        }
        for (DeploymentDescriptor descriptor : deployments) {
            if (matchesAll(podLabels, descriptor.selector())) {
                return descriptor.version();
            }
        }
        return "";
    }

    private boolean matchesAll(Map<String, String> podLabels, Map<String, String> selector) {
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            if (!Objects.equals(podLabels.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> extractSelectorLabels(KubernetesDeployment deployment) {
        if (deployment == null || deployment.spec() == null || deployment.spec().selector() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> matchLabels = deployment.spec().selector().matchLabels();
        return matchLabels != null ? new HashMap<>(matchLabels) : Collections.emptyMap();
    }

    private String extractVersion(KubernetesPodMetadata metadata) {
        if (metadata == null || metadata.labels() == null) {
            return "";
        }
        return metadata.labels().getOrDefault("helpers.app.kubernetes.io/version", "");
    }

    private String extractRelease(Map<String, String> podLabels) {
        if (podLabels == null || podLabels.isEmpty()) {
            return "";
        }
        String branch = podLabels.get("helperrs.app.kubernetes.io/config-branch");
        if (branch == null || branch.isBlank()) {
            branch = podLabels.get("helpers.app.kubernetes.io/config-branch");
        }
        if (branch == null || branch.isBlank()) {
            return "";
        }

        String value = branch.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("release")) {
            value = value.substring("release".length());
            value = value.replaceFirst("^[._\\-/]+", "");
        }
        value = value.replace('_', '.');
        return value;
    }

    private String extractValue(KubernetesContainer container, Function<KubernetesContainer, String> extractor) {
        if (container == null) {
            return "";
        }
        String value = extractor.apply(container);
        return value != null ? value : "";
    }

    private record DeploymentDescriptor(Map<String, String> selector, String version) {}

    private String headersForLogging(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return "<empty>";
        }
        HttpHeaders sanitized = new HttpHeaders();
        headers.forEach((name, values) -> {
            if (isSensitiveHeader(name)) {
                sanitized.put(name, List.of("<masked>"));
            } else {
                sanitized.put(name, values);
            }
        });
        return sanitized.toString();
    }

    private boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        return SENSITIVE_HEADERS.stream().anyMatch(sensitive -> sensitive.equalsIgnoreCase(headerName));
    }
}
