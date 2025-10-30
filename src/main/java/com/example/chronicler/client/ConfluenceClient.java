package com.example.chronicler.client;

import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.exception.ConfluenceClientException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ConfluenceClient(RestTemplate restTemplate, @Value("${confluence.base-url}") String baseUrl) {
        Assert.hasText(baseUrl, "Confluence base URL must not be empty");
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public ConfluencePage getPage(ConfluenceCredentials credentials, String pageId) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("rest", "api", "content", pageId)
                .queryParam("expand", "body.storage,version")
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

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
            return new ConfluencePage(
                    body.id(), body.type(), body.title(), body.version().number(), storageBody != null ? storageBody : "");
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to load Confluence page " + pageId, ex);
        }
    }

    public String uploadAttachment(
            ConfluenceCredentials credentials, String pageId, String fileName, byte[] fileContents) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");
        Assert.hasText(fileName, "fileName must not be empty");
        Assert.notNull(fileContents, "fileContents must not be null");

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
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

        try {
            ResponseEntity<AttachmentResponse> response = restTemplate.exchange(
                    uri, HttpMethod.POST, new HttpEntity<>(multipartBody, headers), AttachmentResponse.class);
            AttachmentResponse body = response.getBody();
            if (body == null || body.results() == null || body.results().isEmpty()) {
                throw new ConfluenceClientException("Confluence did not return attachment metadata for page " + pageId);
            }
            AttachmentResult attachment = body.results().getFirst();
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

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("rest", "api", "content", page.id())
                .build(true)
                .toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        UpdateRequest requestBody = new UpdateRequest(
                page.id(), page.type(), page.title(), new Version(page.version() + 1), new Body(new Storage(updatedBody)));

        try {
            restTemplate.exchange(uri, HttpMethod.PUT, new HttpEntity<>(requestBody, headers), Void.class);
        } catch (RestClientException ex) {
            throw new ConfluenceClientException("Failed to update Confluence page " + page.id(), ex);
        }
    }

    private HttpHeaders authorizationHeaders(ConfluenceCredentials credentials) {
        if (!StringUtils.hasText(credentials.login()) || !StringUtils.hasText(credentials.accessToken())) {
            throw new ConfluenceClientException("Confluence credentials must contain login and access token");
        }
        String authToken = credentials.login() + ":" + credentials.accessToken();
        String encoded = Base64.getEncoder().encodeToString(authToken.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        return headers;
    }

    private record ContentResponse(String id, String type, String title, Version version, Body body) {}

    private record Version(int number) {}

    private record Body(Storage storage) {}

    private record Storage(String value) {}

    private record AttachmentResponse(List<AttachmentResult> results) {}

    private record AttachmentResult(String id, String title) {}

    private record UpdateRequest(String id, String type, String title, Version version, Body body) {}
}
