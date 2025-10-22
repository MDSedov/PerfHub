package com.example.chronicler.client;

import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.exception.GigachatClientException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.web.client.RestTemplate;

@Component
public class GigachatClient {

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
        try {
            ResponseEntity<GigachatTokenResponse> response = restTemplate.postForEntity(
                    oauthUri, entity, GigachatTokenResponse.class);
            GigachatTokenResponse body = response.getBody();
            if (body == null || body.accessToken() == null) {
                throw new GigachatClientException(
                        "GigaChat token response did not contain an access token");
            }
            return body;
        } catch (RestClientException ex) {
            throw new GigachatClientException("Failed to obtain GigaChat access token", ex);
        }
    }

    public JsonNode createCompletion(String accessToken, String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(accessToken);
        headers.set("RqUID", UUID.randomUUID().toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put(
                "messages",
                List.of(Map.of(
                        "role", "user",
                        "content", prompt)));
        payload.put("stream", Boolean.FALSE);
        payload.put("repetition_penalty", Integer.valueOf(1));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    completionsUri, HttpMethod.POST, entity, JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null) {
                throw new GigachatClientException("GigaChat completion response was empty");
            }
            return body;
        } catch (RestClientException ex) {
            throw new GigachatClientException("Failed to request GigaChat completion", ex);
        }
    }
}
