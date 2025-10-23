package com.example.chronicler.client;

import com.example.chronicler.dto.gigachat.GigachatMessage;
import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.exception.GigachatClientException;
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
import org.springframework.beans.factory.annotation.Value;
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

    private final RestTemplate restTemplate;
    private final URI oauthUri;
    private final URI completionsUri;
    private final String scope;
    private final String authorizationKey;
    private final String clientId;
    private final String grantType;
    private final String model;

    public GigachatClient(
            RestTemplate restTemplate,
            @Value("${gigachat.oauth-url}") String oauthUrl,
            @Value("${gigachat.api-url}") String apiUrl,
            @Value("${gigachat.scope}") String scope,
            @Value("${gigachat.authorization-key}") String authorizationKey,
            @Value("${gigachat.client-id}") String clientId,
            @Value("${gigachat.grant-type:client_credentials}") String grantType,
            @Value("${gigachat.model}") String model) {
        Assert.hasText(oauthUrl, "GigaChat OAuth URL must not be empty");
        Assert.hasText(apiUrl, "GigaChat API URL must not be empty");
        Assert.hasText(scope, "GigaChat scope must not be empty");
        Assert.hasText(authorizationKey, "GigaChat authorization key must not be empty");
        Assert.hasText(clientId, "GigaChat client id must not be empty");
        Assert.hasText(grantType, "GigaChat grant type must not be empty");
        Assert.hasText(model, "GigaChat model must not be empty");
        this.restTemplate = restTemplate;
        this.oauthUri = URI.create(oauthUrl);
        this.completionsUri = URI.create(apiUrl);
        this.scope = scope;
        this.authorizationKey = authorizationKey;
        this.clientId = clientId;
        this.grantType = grantType;
        this.model = model;
    }

    public GigachatTokenResponse requestToken() {
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
            ResponseEntity<GigachatTokenResponse> response = restTemplate.postForEntity(
                    oauthUri, entity, GigachatTokenResponse.class);
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

    private static List<Map<String, String>> toMessagePayload(List<GigachatMessage> messages) {
        List<Map<String, String>> payload = new ArrayList<>(messages.size());
        for (GigachatMessage message : messages) {
            Map<String, String> entry = new LinkedHashMap<>();
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
