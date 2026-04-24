package com.example.chronicler.client;

import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.dto.kubernetes.KubernetesConfigMap;
import com.example.chronicler.dto.kubernetes.KubernetesConfigMapListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesContainer;
import com.example.chronicler.dto.kubernetes.KubernetesDeployment;
import com.example.chronicler.dto.kubernetes.KubernetesDeploymentListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesIngress;
import com.example.chronicler.dto.kubernetes.KubernetesIngressListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesIngressRule;
import com.example.chronicler.dto.kubernetes.KubernetesIngressSpec;
import com.example.chronicler.dto.kubernetes.KubernetesNamespaceListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesPod;
import com.example.chronicler.dto.kubernetes.KubernetesPodListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesPodMetadata;
import com.example.chronicler.dto.kubernetes.KubernetesService;
import com.example.chronicler.dto.kubernetes.KubernetesServiceListResponse;
import com.example.chronicler.dto.kubernetes.KubernetesServiceSpec;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.service.ConfigurationProvider;
import com.example.chronicler.util.DropAppConfigurationUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
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
    private final ObjectMapper objectMapper;

    public DropAppClient(
            RestTemplate restTemplate,
            ConfigurationProvider configurationProvider,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
        this.objectMapper = objectMapper;
    }

    /** Получает конфигурацию ресурсов Pod/Container из Kubernetes API для указанных namespace. */
    public List<DropAppPodConfiguration> fetchConfiguration(List<String> namespaces) {
        return fetchConfiguration(namespaces, List.of(DropAppConfigurationComponent.PODS));
    }

    /** Получает конфигурацию выбранных ресурсов Kubernetes из DropApp для указанных namespace. */
    public List<DropAppPodConfiguration> fetchConfiguration(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components) {
        Assert.notEmpty(namespaces, "Список namespace DropApp не должен быть пустым");
        Assert.notEmpty(components, "Список компонентов DropApp не должен быть пустым");
        HttpMethod method = HttpMethod.GET;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(configurationProvider.getRequiredValue(SERVICE, TOKEN_KEY));

        return namespaces.stream()
                .flatMap(namespace -> fetchNamespaceConfiguration(namespace, components, method, headers).stream())
                .collect(Collectors.toList());
    }

    /** Получает YAML-документы выбранных ресурсов Kubernetes из DropApp для указанных namespace. */
    public List<DropAppConfigurationSnapshotDocument> fetchConfigurationDocuments(
            List<String> namespaces,
            List<DropAppConfigurationComponent> components) {
        Assert.notEmpty(namespaces, "Список namespace DropApp не должен быть пустым");
        Assert.notEmpty(components, "Список компонентов DropApp не должен быть пустым");

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(configurationProvider.getRequiredValue(SERVICE, TOKEN_KEY));

        return namespaces.stream()
                .flatMap(namespace -> fetchNamespaceDocuments(namespace, components, HttpMethod.GET, headers).stream())
                .toList();
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

    private URI buildConfigMapsUri(String namespace) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/v1/namespaces/{namespace}/configmaps")
                    .buildAndExpand(namespace)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

    private URI buildServicesUri(String namespace) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/v1/namespaces/{namespace}/services")
                    .buildAndExpand(namespace)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

    private URI buildIngressesUri(String namespace) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/apis/networking.k8s.io/v1/namespaces/{namespace}/ingresses")
                    .buildAndExpand(namespace)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

    private List<DropAppPodConfiguration> fetchNamespaceConfiguration(
            String namespace,
            List<DropAppConfigurationComponent> components,
            HttpMethod method,
            HttpHeaders headers) {
        List<DeploymentDescriptor> deployments = requiresDeployments(components)
                ? fetchDeploymentDescriptors(namespace, method, headers)
                : List.of();
        List<DropAppPodConfiguration> result = new ArrayList<>();
        if (components.contains(DropAppConfigurationComponent.PODS)) {
            result.addAll(fetchNamespacePods(namespace, deployments, method, headers));
        }
        if (components.contains(DropAppConfigurationComponent.DEPLOYMENTS)) {
            result.addAll(fetchNamespaceDeployments(namespace, deployments));
        }
        if (components.contains(DropAppConfigurationComponent.CONFIGMAPS)) {
            result.addAll(fetchNamespaceConfigMaps(namespace, method, headers));
        }
        if (components.contains(DropAppConfigurationComponent.SERVICES)) {
            result.addAll(fetchNamespaceServices(namespace, method, headers));
        }
        if (components.contains(DropAppConfigurationComponent.INGRESS)) {
            result.addAll(fetchNamespaceIngresses(namespace, method, headers));
        }
        return result;
    }

    private List<DropAppConfigurationSnapshotDocument> fetchNamespaceDocuments(
            String namespace,
            List<DropAppConfigurationComponent> components,
            HttpMethod method,
            HttpHeaders headers) {
        List<DropAppConfigurationSnapshotDocument> result = new ArrayList<>();
        if (components.contains(DropAppConfigurationComponent.DEPLOYMENTS)) {
            result.addAll(fetchResourceDocuments(
                    namespace,
                    DropAppConfigurationComponent.DEPLOYMENTS,
                    buildDeploymentsUri(namespace),
                    method,
                    headers));
        }
        if (components.contains(DropAppConfigurationComponent.CONFIGMAPS)) {
            result.addAll(fetchResourceDocuments(
                    namespace,
                    DropAppConfigurationComponent.CONFIGMAPS,
                    buildConfigMapsUri(namespace),
                    method,
                    headers));
        }
        if (components.contains(DropAppConfigurationComponent.SERVICES)) {
            result.addAll(fetchResourceDocuments(
                    namespace,
                    DropAppConfigurationComponent.SERVICES,
                    buildServicesUri(namespace),
                    method,
                    headers));
        }
        if (components.contains(DropAppConfigurationComponent.INGRESS)) {
            result.addAll(fetchResourceDocuments(
                    namespace,
                    DropAppConfigurationComponent.INGRESS,
                    buildIngressesUri(namespace),
                    method,
                    headers));
        }
        return result;
    }

    private List<DropAppConfigurationSnapshotDocument> fetchResourceDocuments(
            String namespace,
            DropAppConfigurationComponent component,
            URI requestUri,
            HttpMethod method,
            HttpHeaders headers) {
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(requestUri, method, request, JsonNode.class);
            JsonNode body = response.getBody();
            JsonNode items = body != null ? body.path("items") : null;
            if (items == null || !items.isArray()) {
                return List.of();
            }
            List<DropAppConfigurationSnapshotDocument> result = new ArrayList<>();
            for (JsonNode item : items) {
                String resourceName = normalizeResourceName(item);
                if (!StringUtils.hasText(resourceName)) {
                    continue;
                }
                result.add(new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        namespace,
                        component.code(),
                        resourceName,
                        toYaml(sanitizeResourceNode(item))));
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw new DropAppClientException(
                    "Не удалось получить YAML для %s Kubernetes: статус %s %s".formatted(
                            component.code(), ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            throw new DropAppClientException(
                    "Не удалось получить YAML для %s Kubernetes: %s".formatted(component.code(), ex.getMessage()),
                    ex);
        }
    }

    private boolean requiresDeployments(List<DropAppConfigurationComponent> components) {
        return components.contains(DropAppConfigurationComponent.PODS)
                || components.contains(DropAppConfigurationComponent.DEPLOYMENTS);
    }

    private List<DropAppPodConfiguration> fetchNamespacePods(
            String namespace,
            List<DeploymentDescriptor> deployments,
            HttpMethod method,
            HttpHeaders headers) {
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
                DeploymentDescriptor deployment = findMatchingDeployment(podLabels, deployments);
                String serviceName = resolvePodServiceName(metadata, deployment);
                String deploymentVersion = resolveVersion(metadata, deployment);
                String release = resolveRelease(podLabels, deployments);
                if (pod.spec() == null || pod.spec().containers() == null) {
                    continue;
                }
                for (KubernetesContainer container : pod.spec().containers()) {
                    String version = resolveContainerVersion(container, deploymentVersion);
                    result.add(new DropAppPodConfiguration(
                            namespace,
                            serviceName,
                            podName,
                            1,
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

    private List<DropAppPodConfiguration> fetchNamespaceDeployments(
            String namespace,
            List<DeploymentDescriptor> deployments) {
        return deployments.stream()
                .map(deployment -> new DropAppPodConfiguration(
                        namespace,
                        DropAppConfigurationComponent.DEPLOYMENTS.label(),
                        deployment.name(),
                        formatDeploymentDetails(deployment.selector()),
                        deployment.version(),
                        deployment.release(),
                        "",
                        "",
                        "",
                        ""))
                .toList();
    }

    private List<DropAppPodConfiguration> fetchNamespaceConfigMaps(
            String namespace,
            HttpMethod method,
            HttpHeaders headers) {
        URI requestUri = buildConfigMapsUri(namespace);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<KubernetesConfigMapListResponse> response = restTemplate.exchange(
                    requestUri, method, request, KubernetesConfigMapListResponse.class);
            KubernetesConfigMapListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            List<DropAppPodConfiguration> result = new ArrayList<>(body.items().size());
            for (KubernetesConfigMap configMap : body.items()) {
                KubernetesPodMetadata metadata = configMap != null ? configMap.metadata() : null;
                String name = metadata != null ? metadata.name() : null;
                String details = "Ключей: " + (configMap != null && configMap.data() != null ? configMap.data().size() : 0);
                result.add(new DropAppPodConfiguration(
                        namespace,
                        DropAppConfigurationComponent.CONFIGMAPS.label(),
                        name,
                        details,
                        extractVersion(metadata),
                        extractReleaseFromLabels(metadata != null ? metadata.labels() : null),
                        "",
                        "",
                        "",
                        ""));
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw new DropAppClientException(
                    "Не удалось получить список configmaps Kubernetes: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            throw new DropAppClientException("Не удалось получить список configmaps Kubernetes: " + ex.getMessage(), ex);
        }
    }

    private List<DropAppPodConfiguration> fetchNamespaceServices(
            String namespace,
            HttpMethod method,
            HttpHeaders headers) {
        URI requestUri = buildServicesUri(namespace);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<KubernetesServiceListResponse> response = restTemplate.exchange(
                    requestUri, method, request, KubernetesServiceListResponse.class);
            KubernetesServiceListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            List<DropAppPodConfiguration> result = new ArrayList<>(body.items().size());
            for (KubernetesService service : body.items()) {
                KubernetesPodMetadata metadata = service != null ? service.metadata() : null;
                KubernetesServiceSpec spec = service != null ? service.spec() : null;
                result.add(new DropAppPodConfiguration(
                        namespace,
                        DropAppConfigurationComponent.SERVICES.label(),
                        metadata != null ? metadata.name() : null,
                        formatServiceDetails(spec),
                        extractVersion(metadata),
                        extractReleaseFromLabels(metadata != null ? metadata.labels() : null),
                        "",
                        "",
                        "",
                        ""));
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw new DropAppClientException(
                    "Не удалось получить список services Kubernetes: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            throw new DropAppClientException("Не удалось получить список services Kubernetes: " + ex.getMessage(), ex);
        }
    }

    private List<DropAppPodConfiguration> fetchNamespaceIngresses(
            String namespace,
            HttpMethod method,
            HttpHeaders headers) {
        URI requestUri = buildIngressesUri(namespace);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<KubernetesIngressListResponse> response = restTemplate.exchange(
                    requestUri, method, request, KubernetesIngressListResponse.class);
            KubernetesIngressListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            List<DropAppPodConfiguration> result = new ArrayList<>(body.items().size());
            for (KubernetesIngress ingress : body.items()) {
                KubernetesPodMetadata metadata = ingress != null ? ingress.metadata() : null;
                KubernetesIngressSpec spec = ingress != null ? ingress.spec() : null;
                result.add(new DropAppPodConfiguration(
                        namespace,
                        DropAppConfigurationComponent.INGRESS.label(),
                        metadata != null ? metadata.name() : null,
                        formatIngressDetails(spec),
                        extractVersion(metadata),
                        extractReleaseFromLabels(metadata != null ? metadata.labels() : null),
                        "",
                        "",
                        "",
                        ""));
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw new DropAppClientException(
                    "Не удалось получить список ingress Kubernetes: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            throw new DropAppClientException("Не удалось получить список ingress Kubernetes: " + ex.getMessage(), ex);
        }
    }

    /** Получает список доступных namespace из Kubernetes API. */
    public List<String> fetchNamespaces() {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        URI requestUri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/v1/namespaces")
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(configurationProvider.getRequiredValue(SERVICE, TOKEN_KEY));

        if (log.isDebugEnabled()) {
            log.debug("Запрос списка namespaces DropApp по адресу {}", requestUri);
        }

        try {
            ResponseEntity<KubernetesNamespaceListResponse> response = restTemplate.exchange(
                    requestUri, HttpMethod.GET, new HttpEntity<>(headers), KubernetesNamespaceListResponse.class);
            KubernetesNamespaceListResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return List.of();
            }
            return body.items().stream()
                    .map(ns -> ns != null ? ns.name() : null)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
        } catch (RestClientResponseException ex) {
            throw new DropAppClientException("Не удалось получить список namespace DropApp: " + ex.getStatusText(), ex);
        } catch (RestClientException ex) {
            throw new DropAppClientException("Не удалось получить список namespace DropApp", ex);
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
                descriptors.add(new DeploymentDescriptor(
                        selector,
                        metadata != null ? metadata.name() : null,
                        version,
                        extractReleaseFromLabels(metadata != null ? metadata.labels() : null)));
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

    private DeploymentDescriptor findMatchingDeployment(
            Map<String, String> podLabels,
            List<DeploymentDescriptor> deployments) {
        if (podLabels == null || podLabels.isEmpty() || deployments == null || deployments.isEmpty()) {
            return null;
        }
        for (DeploymentDescriptor descriptor : deployments) {
            if (matchesAll(podLabels, descriptor.selector())) {
                return descriptor;
            }
        }
        return null;
    }

    private String resolveVersion(KubernetesPodMetadata metadata, DeploymentDescriptor deployment) {
        if (deployment != null && StringUtils.hasText(deployment.version())) {
            return deployment.version();
        }
        return extractVersion(metadata);
    }

    private String resolvePodServiceName(KubernetesPodMetadata metadata, DeploymentDescriptor deployment) {
        if (deployment != null && StringUtils.hasText(deployment.name())) {
            return deployment.name().trim();
        }
        String labelServiceName = extractServiceNameFromLabels(metadata != null ? metadata.labels() : null);
        if (StringUtils.hasText(labelServiceName)) {
            return labelServiceName;
        }
        return DropAppConfigurationUtils.deriveServiceNameFromPodName(metadata != null ? metadata.name() : null);
    }

    private String extractServiceNameFromLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        return Stream.of(
                        labels.get("app.kubernetes.io/name"),
                        labels.get("app"),
                        labels.get("k8s-app"),
                        labels.get("name"))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    private String resolveContainerVersion(KubernetesContainer container, String fallbackVersion) {
        String imageTag = extractImageTag(container != null ? container.image() : null);
        if (StringUtils.hasText(imageTag)) {
            return imageTag;
        }
        return fallbackVersion != null ? fallbackVersion : "";
    }

    private String extractImageTag(String image) {
        if (!StringUtils.hasText(image)) {
            return "";
        }
        String normalized = image.trim();
        int digestIndex = normalized.indexOf('@');
        if (digestIndex >= 0) {
            normalized = normalized.substring(0, digestIndex);
        }
        int lastSlashIndex = normalized.lastIndexOf('/');
        int lastColonIndex = normalized.lastIndexOf(':');
        if (lastColonIndex <= lastSlashIndex || lastColonIndex == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(lastColonIndex + 1).trim();
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

    private String resolveRelease(Map<String, String> podLabels, List<DeploymentDescriptor> deployments) {
        String release = extractReleaseFromLabels(podLabels);
        if (!release.isBlank()) {
            return release;
        }
        if (deployments != null) {
            for (DeploymentDescriptor descriptor : deployments) {
                if (matchesAll(podLabels, descriptor.selector()) && !descriptor.release().isBlank()) {
                    return descriptor.release();
                }
            }
        }
        return "";
    }

    private String extractReleaseFromLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        String branch = labels.get("helpers.app.kubernetes.io/config-branch");
        if (branch == null || branch.isBlank()) {
            branch = labels.get("helperrs.app.kubernetes.io/config-branch");
        }
        return normalizeRelease(branch);
    }

    private String normalizeRelease(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("release")) {
            normalized = normalized.substring("release".length());
        }
        normalized = normalized.replaceFirst("^[._\\-/]+", "");
        normalized = normalized.replace('_', '.');
        normalized = normalized.replaceAll("\\s+", "");
        return normalized;
    }

    private String extractValue(KubernetesContainer container, Function<KubernetesContainer, String> extractor) {
        if (container == null) {
            return "";
        }
        String value = extractor.apply(container);
        return value != null ? value : "";
    }

    private String formatDeploymentDetails(Map<String, String> selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }
        return selector.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String formatServiceDetails(KubernetesServiceSpec spec) {
        if (spec == null) {
            return "";
        }
        List<String> parts = new ArrayList<>(2);
        if (StringUtils.hasText(spec.type())) {
            parts.add("Тип: " + spec.type().trim());
        }
        if (StringUtils.hasText(spec.clusterIp())) {
            parts.add("ClusterIP: " + spec.clusterIp().trim());
        }
        return String.join(", ", parts);
    }

    private String formatIngressDetails(KubernetesIngressSpec spec) {
        if (spec == null) {
            return "";
        }
        List<String> parts = new ArrayList<>(2);
        if (StringUtils.hasText(spec.ingressClassName())) {
            parts.add("Класс: " + spec.ingressClassName().trim());
        }
        String hosts = spec.rules() == null
                ? ""
                : spec.rules().stream()
                        .map(KubernetesIngressRule::host)
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .collect(Collectors.joining(", "));
        if (StringUtils.hasText(hosts)) {
            parts.add("Хосты: " + hosts);
        }
        return String.join(", ", parts);
    }

    private String normalizeResourceName(JsonNode item) {
        if (item == null) {
            return null;
        }
        JsonNode metadata = item.path("metadata");
        if (metadata.isMissingNode() || metadata.isNull()) {
            return null;
        }
        String name = metadata.path("name").asText(null);
        return StringUtils.hasText(name) ? name.trim() : null;
    }

    private JsonNode sanitizeResourceNode(JsonNode source) {
        if (!(source instanceof ObjectNode objectNode)) {
            return source;
        }
        ObjectNode root = objectNode.deepCopy();
        root.remove("status");
        JsonNode metadataNode = root.get("metadata");
        if (metadataNode instanceof ObjectNode metadata) {
            metadata.remove(List.of(
                    "managedFields",
                    "resourceVersion",
                    "uid",
                    "creationTimestamp",
                    "generation",
                    "selfLink"));
        }
        return root;
    }

    private String toYaml(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        appendYamlNode(builder, node, 0);
        return builder.toString().stripTrailing();
    }

    private void appendYamlNode(StringBuilder builder, JsonNode node, int indent) {
        if (node == null || node.isNull()) {
            builder.append("null\n");
            return;
        }
        if (node.isObject()) {
            if (!node.fieldNames().hasNext()) {
                builder.append("{}\n");
                return;
            }
            node.fields().forEachRemaining(entry -> appendYamlField(builder, entry.getKey(), entry.getValue(), indent));
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                builder.append("[]\n");
                return;
            }
            node.forEach(item -> appendYamlArrayItem(builder, item, indent));
            return;
        }
        indent(builder, indent);
        builder.append(formatYamlScalar(node, indent)).append('\n');
    }

    private void appendYamlField(StringBuilder builder, String key, JsonNode value, int indent) {
        indent(builder, indent);
        builder.append(formatYamlKey(key)).append(':');
        if (isInlineYamlValue(value)) {
            builder.append(' ').append(formatYamlScalar(value, indent)).append('\n');
            return;
        }
        builder.append('\n');
        appendYamlNode(builder, value, indent + 2);
    }

    private void appendYamlArrayItem(StringBuilder builder, JsonNode value, int indent) {
        indent(builder, indent);
        builder.append('-');
        if (isInlineYamlValue(value)) {
            builder.append(' ').append(formatYamlScalar(value, indent)).append('\n');
            return;
        }
        builder.append('\n');
        appendYamlNode(builder, value, indent + 2);
    }

    private boolean isInlineYamlValue(JsonNode value) {
        return value == null
                || value.isNull()
                || value.isValueNode()
                || (value.isArray() && value.isEmpty())
                || (value.isObject() && value.isEmpty());
    }

    private String formatYamlKey(String key) {
        if (key != null && key.matches("[A-Za-z0-9_.-]+")) {
            return key;
        }
        return quoteYamlString(key);
    }

    private String formatYamlScalar(JsonNode value, int indent) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean() || value.isNumber()) {
            return value.asText();
        }
        if (value.isTextual()) {
            return formatYamlString(value.textValue(), indent);
        }
        if (value.isArray() && value.isEmpty()) {
            return "[]";
        }
        if (value.isObject() && value.isEmpty()) {
            return "{}";
        }
        return quoteYamlString(value.toString());
    }

    private String formatYamlString(String value, int indent) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.contains("\n")) {
            StringBuilder builder = new StringBuilder("|-\n");
            String[] lines = normalized.split("\n", -1);
            for (String line : lines) {
                indent(builder, indent + 2);
                builder.append(line).append('\n');
            }
            return builder.toString().stripTrailing();
        }
        return quoteYamlString(normalized);
    }

    private String quoteYamlString(String value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : "");
        } catch (JsonProcessingException ex) {
            throw new DropAppClientException("Не удалось сериализовать YAML-строку", ex);
        }
    }

    private void indent(StringBuilder builder, int indent) {
        for (int index = 0; index < indent; index += 1) {
            builder.append(' ');
        }
    }

    private record DeploymentDescriptor(Map<String, String> selector, String name, String version, String release) {}

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
