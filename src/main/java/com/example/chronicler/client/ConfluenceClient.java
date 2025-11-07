package com.example.chronicler.client;

import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClient.class);
    private static final String SERVICE = "confluence";
    private static final String BASE_URL_KEY = "base-url";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public ConfluenceClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    public ConfluencePage getPage(ConfluenceCredentials credentials, String pageId) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl())
                .pathSegment("rest", "api", "content", pageId)
                .queryParam("expand", "body.storage,version,space")
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);
        logConfluenceRequest("GET", uri, headers, "pageId=%s".formatted(pageId));
        try {
            ResponseEntity<ContentResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), ContentResponse.class);
            ContentResponse body = response.getBody();
            if (body == null) {
                throw new ConfluenceClientException("Confluence returned an empty response for page " + pageId);
            }
            if (body.version() == null) {
                throw new ConfluenceClientException("Confluence response for page " + pageId + " is missing version info");
            }
            if (body.body() == null || body.body().storage() == null) {
                throw new ConfluenceClientException("Confluence response for page " + pageId + " is missing body storage");
            }
            String storageBody = body.body().storage().value();
            String spaceKey = body.space() != null ? body.space().key() : null;
            return new ConfluencePage(
                    body.id(),
                    body.type(),
                    body.title(),
                    body.version().number(),
                    storageBody != null ? storageBody : "",
                    spaceKey);
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to load Confluence page " + pageId, ex);
        }
    }

    public ConfluencePage createChildPage(
            ConfluenceCredentials credentials,
            String parentPageId,
            String spaceKey,
            String title,
            String body) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(parentPageId, "parentPageId must not be empty");
        Assert.hasText(spaceKey, "spaceKey must not be empty");
        Assert.hasText(title, "title must not be empty");
        Assert.notNull(body, "body must not be null");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl())
                .pathSegment("rest", "api", "content")
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateRequest requestBody = new CreateRequest(
                title,
                "page",
                new Space(spaceKey),
                List.of(new Ancestor(parentPageId)),
                new Body(new Storage(body, "storage")));

        logConfluenceRequest(
                "POST", uri, headers, "parentPageId=%s, title=%s".formatted(parentPageId, title));
        try {
            ResponseEntity<ContentResponse> response = restTemplate.exchange(
                    uri, HttpMethod.POST, new HttpEntity<>(requestBody, headers), ContentResponse.class);
            ContentResponse responseBody = response.getBody();
            if (responseBody == null || responseBody.version() == null) {
                throw new ConfluenceClientException(
                        "Confluence did not return created page metadata for parent " + parentPageId);
            }
            String storageBody = responseBody.body() != null && responseBody.body().storage() != null
                    ? responseBody.body().storage().value()
                    : "";
            String responseSpaceKey = responseBody.space() != null ? responseBody.space().key() : spaceKey;
            return new ConfluencePage(
                    responseBody.id(),
                    responseBody.type(),
                    responseBody.title(),
                    responseBody.version().number(),
                    storageBody != null ? storageBody : "",
                    responseSpaceKey);
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to create Confluence child page under " + parentPageId, ex);
        }
    }

    public String uploadAttachment(
            ConfluenceCredentials credentials, String pageId, String fileName, byte[] fileContents) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");
        Assert.hasText(fileName, "fileName must not be empty");
        Assert.notNull(fileContents, "fileContents must not be null");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl())
                .pathSegment("rest", "api", "content", pageId, "child", "attachment")
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Atlassian-Token", "no-check");

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentDispositionFormData("file", fileName);
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        ByteArrayResource resource = new ByteArrayResource(fileContents) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        multipartBody.add("file", new HttpEntity<>(resource, fileHeaders));

        logConfluenceRequest(
                "POST", uri, headers, "pageId=%s, fileName=%s".formatted(pageId, fileName));
        try {
            ResponseEntity<AttachmentResponse> response = restTemplate.exchange(
                    uri, HttpMethod.POST, new HttpEntity<>(multipartBody, headers), AttachmentResponse.class);
            AttachmentResponse body = response.getBody();
            if (body == null || body.results() == null || body.results().isEmpty()) {
                throw new ConfluenceClientException("Confluence did not return attachment metadata for page " + pageId);
            }
            AttachmentResult attachment = body.results().get(0);
            log.info(
                    "Uploaded attachment '{}' to Confluence page {} (attachment id: {})",
                    attachment.title(),
                    pageId,
                    attachment.id());
            return attachment.title();
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to upload attachment to Confluence page " + pageId, ex);
        }
    }

    public void updatePage(ConfluenceCredentials credentials, ConfluencePage page, String updatedBody) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.notNull(page, "page must not be null");
        Assert.notNull(updatedBody, "updatedBody must not be null");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl())
                .pathSegment("rest", "api", "content", page.id())
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);

        UpdateRequest requestBody = new UpdateRequest(
                page.id(),
                page.type(),
                page.title(),
                new Version(page.version() + 1),
                new Body(new Storage(updatedBody, "storage")));

        logConfluenceRequest("PUT", uri, headers, "pageId=%s, title=%s".formatted(page.id(), page.title()));
        try {
            restTemplate.exchange(uri, HttpMethod.PUT, new HttpEntity<>(requestBody, headers), Void.class);
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to update Confluence page " + page.id(), ex);
        }
    }

    private HttpHeaders authorizationHeaders(ConfluenceCredentials credentials) {
        if (!StringUtils.hasText(credentials.accessToken())) {
            throw new ConfluenceClientException("Confluence credentials must contain access token");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Confluence-Token", credentials.accessToken());
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.accessToken());
        return headers;
    }

    private String baseUrl() {
        return configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY);
    }

    private record ContentResponse(String id, String type, String title, Version version, Body body, Space space) {}

    private record Version(int number) {}

    private record Body(Storage storage) {}

    private record Storage(String value, String representation) {}

    private record AttachmentResponse(List<AttachmentResult> results) {}

    private record AttachmentResult(String id, String title) {}

    private record UpdateRequest(String id, String type, String title, Version version, Body body) {}

    private record CreateRequest(String title, String type, Space space, List<Ancestor> ancestors, Body body) {}

    private record Space(String key) {}

    private record Ancestor(String id) {}

    private void logConfluenceRequest(String method, URI uri, HttpHeaders headers, String context) {
        log.info(
                "Confluence request: {} {} headers={} {}",
                method,
                uri,
                headers.toSingleValueMap(),
                context != null ? context : "");
    }
}
