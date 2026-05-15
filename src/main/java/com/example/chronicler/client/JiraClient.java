package com.example.chronicler.client;

import com.example.chronicler.exception.JiraClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);
    private static final Pattern JSON_MESSAGE_PATTERN =
            Pattern.compile("\"(?:message|error|errorMessages)\"\\s*:\\s*(\"([^\"]+)\"|\\[\"([^\"]+)\"\\])",
                    Pattern.CASE_INSENSITIVE);
    private static final String SERVICE = "jira";
    private static final String BASE_URL_KEY = "base-url";
    private static final String API_PATH_KEY = "api-path";
    private static final String TOKEN_KEY = "personal-access-token";
    private static final String DEFAULT_API_PATH = "/rest/api/2";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public JiraClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    public String checkConnection() {
        try {
            URI uri = apiUriBuilder().pathSegment("myself").build(true).toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
            headers.setBearerAuth(required(TOKEN_KEY));

            log.debug("Checking Jira connection via {}", uri);
            ResponseEntity<String> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getStatusCode().toString();
        } catch (RestClientException ex) {
            throw new JiraClientException(buildErrorMessage("Failed to check Jira connection", ex), ex);
        } catch (IllegalArgumentException ex) {
            throw new JiraClientException("Invalid Jira connection configuration: " + ex.getMessage(), ex);
        }
    }

    private UriComponentsBuilder apiUriBuilder() {
        return UriComponentsBuilder.fromHttpUrl(baseUrl()).path(apiPath());
    }

    private String baseUrl() {
        String url = required(BASE_URL_KEY).trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String apiPath() {
        String configured = configurationProvider.findValue(SERVICE, API_PATH_KEY)
                .map(StringUtils::trimWhitespace)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_API_PATH);
        if (!configured.startsWith("/")) {
            configured = "/" + configured;
        }
        while (configured.endsWith("/") && configured.length() > 1) {
            configured = configured.substring(0, configured.length() - 1);
        }
        return configured;
    }

    private String required(String key) {
        try {
            return configurationProvider.getRequiredValue(SERVICE, key);
        } catch (IllegalStateException ex) {
            throw new JiraClientException(
                    "Configuration parameter not found for service '%s' and key '%s'"
                            .formatted(SERVICE, key),
                    ex);
        }
    }

    private String buildErrorMessage(String prefix, RestClientException ex) {
        String details = extractErrorDetails(ex);
        return StringUtils.hasText(details) ? prefix + ": " + details : prefix;
    }

    private String extractErrorDetails(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            String status = "HTTP " + responseException.getRawStatusCode();
            if (StringUtils.hasText(responseException.getStatusText())) {
                status += " " + responseException.getStatusText();
            }
            String responseBody = normalizeMessage(responseException.getResponseBodyAsString());
            if (!StringUtils.hasText(responseBody)) {
                return status;
            }
            String extractedMessage = extractStructuredMessage(responseBody);
            return status + ": " + truncateMessage(
                    StringUtils.hasText(extractedMessage) ? extractedMessage : responseBody);
        }
        return truncateMessage(normalizeMessage(ex.getMessage()));
    }

    private String extractStructuredMessage(String responseBody) {
        Matcher matcher = JSON_MESSAGE_PATTERN.matcher(responseBody);
        if (matcher.find()) {
            String message = StringUtils.hasText(matcher.group(2)) ? matcher.group(2) : matcher.group(3);
            return normalizeMessage(message);
        }
        return null;
    }

    private String normalizeMessage(String message) {
        return message == null ? "" : message.replaceAll("\\s+", " ").trim();
    }

    private String truncateMessage(String message) {
        if (!StringUtils.hasText(message) || message.length() <= 300) {
            return message;
        }
        return message.substring(0, 297) + "...";
    }
}
