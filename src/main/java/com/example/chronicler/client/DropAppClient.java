package com.example.chronicler.client;

import com.example.chronicler.dto.dropapp.DropAppConfigurationResponse;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    /** Получает конфигурацию DropApp для указанных namespace. */
    public List<DropAppPodConfiguration> fetchConfiguration(List<String> namespaces) {
        Assert.notEmpty(namespaces, "Список namespace DropApp не должен быть пустым");
        URI requestUri = buildConfigurationUri(namespaces);
        HttpMethod method = HttpMethod.GET;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(configurationProvider.getRequiredValue(SERVICE, TOKEN_KEY));

        HttpEntity<Void> request = new HttpEntity<>(headers);
        if (log.isDebugEnabled()) {
            log.debug("Запрос конфигурации DropApp по адресу {} для namespace {}", requestUri, namespaces);
            log.debug(
                    "DropApp request details: {} {} with headers {}",
                    method,
                    requestUri,
                    headersForLogging(headers));
        }
        try {
            ResponseEntity<DropAppConfigurationResponse> response = restTemplate.exchange(
                    requestUri, method, request, DropAppConfigurationResponse.class);
            DropAppConfigurationResponse body = response.getBody();
            if (body == null || body.items() == null) {
                return Collections.emptyList();
            }
            return body.items();
        } catch (RestClientResponseException ex) {
            log.error(
                    "Запрос к DropApp завершился ошибкой {} {}. Запрос: {} {}. Заголовки запроса: {}. "
                            + "Заголовки ответа: {}. Тело ответа: {}",
                    ex.getRawStatusCode(),
                    ex.getStatusText(),
                    method,
                    requestUri,
                    headersForLogging(headers),
                    headersForLogging(ex.getResponseHeaders()),
                    ex.getResponseBodyAsString());
            throw new DropAppClientException(
                    "Не удалось получить конфигурацию DropApp: статус %s %s".formatted(
                            ex.getRawStatusCode(), ex.getStatusText()),
                    ex);
        } catch (RestClientException ex) {
            log.error(
                    "Ошибка HTTP-клиента при обращении к DropApp. Запрос: {} {}. Заголовки запроса: {}",
                    method,
                    requestUri,
                    headersForLogging(headers),
                    ex);
            throw new DropAppClientException("Не удалось получить конфигурацию DropApp: " + ex.getMessage(), ex);
        }
    }

    /** Формирует полный URI запроса конфигурации DropApp. */
    private URI buildConfigurationUri(List<String> namespaces) {
        String baseUrl = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/v1/configuration")
                    .queryParam("namespaces", String.join(",", namespaces))
                    .build(true)
                    .toUri();
        } catch (IllegalArgumentException ex) {
            throw new DropAppClientException(
                    "Некорректный базовый URL DropApp: " + Objects.toString(baseUrl, "<null>"), ex);
        }
    }

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
