package perfhub.client;

import perfhub.dto.gigachat.GigachatMessage;
import perfhub.dto.gigachat.GigachatTokenResponse;
import perfhub.exception.GigachatClientException;
import perfhub.service.ConfigurationProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLHandshakeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class GigachatClient {

    private static final Logger log = LoggerFactory.getLogger(GigachatClient.class);
    private static final String SERVICE = "gigachat";
    private static final String OAUTH_URL_KEY = "oauth-url";
    private static final String API_URL_KEY = "api-url";
    private static final String SCOPE_KEY = "scope";
    private static final String AUTHORIZATION_KEY_KEY = "authorization-key";
    private static final String CLIENT_ID_KEY = "client-id";
    private static final String GRANT_TYPE_KEY = "grant-type";
    private static final String MODEL_KEY = "model";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public GigachatClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    public GigachatTokenResponse requestToken() {
        URI oauthUri = resolveUri(OAUTH_URL_KEY);
        String scope = required(SCOPE_KEY);
        String authorizationKey = required(AUTHORIZATION_KEY_KEY);
        String clientId = required(CLIENT_ID_KEY);
        String grantType = required(GRANT_TYPE_KEY);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("RqUID", UUID.randomUUID().toString());
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + authorizationKey);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("scope", scope);
        form.add("client_id", clientId);
        form.add("grant_type", grantType);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Requesting GigaChat token via {} with scope='{}', client_id='{}', grant_type='{}'",
                    oauthUri,
                    scope,
                    clientId,
                    grantType);
        }
        try {
            ResponseEntity<GigachatTokenResponse> response =
                    restTemplate.postForEntity(oauthUri, entity, GigachatTokenResponse.class);
            if (log.isDebugEnabled()) {
                log.debug(
                        "GigaChat token response status: {}, body: {}",
                        response.getStatusCode(),
                        response.getBody());
            }
            GigachatTokenResponse body = response.getBody();
            if (body == null || body.accessToken() == null) {
                throw new GigachatClientException(
                        "GigaChat token response did not contain an access token");
            }
            return body;
        } catch (RestClientResponseException ex) {
            log.error(
                    "GigaChat token request failed with status {} and body {}",
                    ex.getRawStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex);
            String message = String.format(
                    "Failed to obtain GigaChat access token (status %s %s)",
                    ex.getRawStatusCode(),
                    ex.getStatusText());
            throw new GigachatClientException(message, ex);
        } catch (RestClientException ex) {
            log.error("GigaChat token request failed", ex);
            String message = String.format(
                    "Failed to obtain GigaChat access token: %s",
                    ex.getMessage());
            if (isSslHandshakeFailure(ex)) {
                message +=
                        ". Ensure the GigaChat TLS certificate is trusted, configure gigachat.trust-store, "
                                + "or enable gigachat.disable-ssl-validation for local development";
            }
            throw new GigachatClientException(message, ex);
        }
    }

    public JsonNode createCompletion(String accessToken, List<GigachatMessage> messages) {
        URI completionsUri = resolveUri(API_URL_KEY);
        String model = required(MODEL_KEY);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(accessToken);
        headers.set("RqUID", UUID.randomUUID().toString());

        Assert.notEmpty(messages, "GigaChat messages must not be empty");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", toMessagePayload(messages));
        payload.put("stream", Boolean.FALSE);
        payload.put("repetition_penalty", Integer.valueOf(1));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Requesting GigaChat completion via {} with model='{}' and {} message(s)",
                    completionsUri,
                    model,
                    messages.size());
        }
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    completionsUri, HttpMethod.POST, entity, JsonNode.class);
            if (log.isDebugEnabled()) {
                log.debug(
                        "GigaChat completion response status: {}, body: {}",
                        response.getStatusCode(),
                        response.getBody());
            }
            JsonNode body = response.getBody();
            if (body == null) {
                throw new GigachatClientException("GigaChat completion response was empty");
            }
            return body;
        } catch (RestClientResponseException ex) {
            log.error(
                    "GigaChat completion request failed with status {} and body {}",
                    ex.getRawStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex);
            String message = String.format(
                    "Failed to request GigaChat completion (status %s %s)",
                    ex.getRawStatusCode(),
                    ex.getStatusText());
            throw new GigachatClientException(message, ex);
        } catch (RestClientException ex) {
            log.error("GigaChat completion request failed", ex);
            String message = String.format(
                    "Failed to request GigaChat completion: %s",
                    ex.getMessage());
            throw new GigachatClientException(message, ex);
        }
    }

    private String required(String key) {
        return configurationProvider.getRequiredValue(SERVICE, key);
    }

    private URI resolveUri(String key) {
        String value = required(key);
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new GigachatClientException(
                    "Configuration value for %s.%s is not a valid URI: %s".formatted(SERVICE, key, value), ex);
        }
    }

    private static List<Map<String, Object>> toMessagePayload(List<GigachatMessage> messages) {
        List<Map<String, Object>> payload = new ArrayList<>(messages.size());
        for (GigachatMessage message : messages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());
            entry.put("content", message.content());
            payload.add(entry);
        }
        return payload;
    }

    private static boolean isSslHandshakeFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
