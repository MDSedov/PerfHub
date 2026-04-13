package com.example.chronicler.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePageDescendant;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluencePageSummary;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
public class ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClient.class);
    private static final Pattern JSON_MESSAGE_PATTERN =
            Pattern.compile("\"(?:message|reason)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final String SERVICE = "confluence";
    private static final String BASE_URL_KEY = "base-url";
    private static final String API_PATH_KEY = "api-path";

    private final RestTemplate restTemplate;
    private final ConfigurationProvider configurationProvider;

    public ConfluenceClient(RestTemplate restTemplate, ConfigurationProvider configurationProvider) {
        this.restTemplate = restTemplate;
        this.configurationProvider = configurationProvider;
    }

    public ConfluencePage getPage(ConfluenceCredentials credentials, String pageId) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");

        URI uri = apiUriBuilder()
                .pathSegment("content", pageId)
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
            throw new ConfluenceClientException(buildErrorMessage("Failed to load Confluence page " + pageId, ex), ex);
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

        URI uri = apiUriBuilder().pathSegment("content").build(true).toUri();

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
            throw new ConfluenceClientException(
                    buildErrorMessage("Failed to create Confluence child page under " + parentPageId, ex),
                    ex);
        }
    }

    public List<ConfluencePageSummary> listChildPages(ConfluenceCredentials credentials, String parentPageId) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(parentPageId, "parentPageId must not be empty");

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);

        int start = 0;
        final int limit = 200;
        List<ConfluencePageSummary> childPages = new ArrayList<>();

        while (true) {
            URI uri = apiUriBuilder()
                    .pathSegment("content", parentPageId, "child", "page")
                    .queryParam("limit", limit)
                    .queryParam("start", start)
                    .build(true)
                    .toUri();
            logConfluenceRequest(
                    "GET",
                    uri,
                    headers,
                    "parentPageId=%s, limit=%s, start=%s".formatted(parentPageId, limit, start));
            try {
                ResponseEntity<ChildPagesResponse> response = restTemplate.exchange(
                        uri, HttpMethod.GET, new HttpEntity<>(headers), ChildPagesResponse.class);
                ChildPagesResponse body = response.getBody();
                if (body == null || body.results() == null || body.results().isEmpty()) {
                    break;
                }
                body.results().forEach(page -> childPages.add(new ConfluencePageSummary(
                        page.id(),
                        StringUtils.hasText(page.title()) ? page.title() : page.id())));
                if (body.results().size() < limit) {
                    break;
                }
                start += body.results().size();
            } catch (RestClientException ex) {
                throw new ConfluenceClientException(
                        buildErrorMessage("Failed to load Confluence child pages for " + parentPageId, ex),
                        ex);
            }
        }

        return List.copyOf(childPages);
    }

    public List<ConfluencePageDescendant> listPageDescendants(ConfluenceCredentials credentials, String rootPageId) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(rootPageId, "rootPageId must not be empty");

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            return loadDescendantsFromDescendantEndpoint(credentials, rootPageId, headers);
        } catch (ConfluenceClientException ex) {
            log.warn(
                    "Confluence descendant endpoint failed for root page {}. Falling back to search API. Reason: {}",
                    rootPageId,
                    ex.getMessage());
            return loadDescendantsFromSearchEndpoint(credentials, rootPageId, headers, ex);
        }
    }

    public String uploadAttachment(
            ConfluenceCredentials credentials, String pageId, String fileName, byte[] fileContents) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.hasText(pageId, "pageId must not be empty");
        Assert.hasText(fileName, "fileName must not be empty");
        Assert.notNull(fileContents, "fileContents must not be null");

        URI uri = apiUriBuilder().pathSegment("content", pageId, "child", "attachment").build(true).toUri();

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
            throw new ConfluenceClientException(
                    buildErrorMessage("Failed to upload attachment to Confluence page " + pageId, ex),
                    ex);
        }
    }

    public void updatePage(ConfluenceCredentials credentials, ConfluencePage page, String updatedBody) {
        Assert.notNull(credentials, "credentials must not be null");
        Assert.notNull(page, "page must not be null");
        Assert.notNull(updatedBody, "updatedBody must not be null");

        URI uri = apiUriBuilder().pathSegment("content", page.id()).build(true).toUri();

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
            throw new ConfluenceClientException(
                    buildErrorMessage("Failed to update Confluence page " + page.id(), ex),
                    ex);
        }
    }

    public String buildPageUrl(String pageId) {
        Assert.hasText(pageId, "pageId must not be empty");
        return UriComponentsBuilder.fromHttpUrl(baseUrl())
                .path("/pages/viewpage.action")
                .queryParam("pageId", pageId)
                .build(true)
                .toUriString();
    }

    public String checkConnection(ConfluenceCredentials credentials) {
        Assert.notNull(credentials, "credentials must not be null");

        URI uri = apiUriBuilder().pathSegment("space").queryParam("limit", 1).build(true).toUri();

        HttpHeaders headers = authorizationHeaders(credentials);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        logConfluenceRequest("GET", uri, headers, "limit=1");
        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getStatusCode().toString();
        } catch (RestClientException ex) {
            throw new ConfluenceClientException(buildErrorMessage("Failed to check Confluence connection", ex), ex);
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
            return normalizeMessage(matcher.group(1));
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
        String url = configurationProvider.getRequiredValue(SERVICE, BASE_URL_KEY).trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private UriComponentsBuilder apiUriBuilder() {
        return UriComponentsBuilder.fromHttpUrl(baseUrl()).path(apiPath());
    }

    private String apiPath() {
        String configured = configurationProvider.findValue(SERVICE, API_PATH_KEY)
                .map(StringUtils::trimWhitespace)
                .filter(StringUtils::hasText)
                .orElse("/rest/api");
        if (!configured.startsWith("/")) {
            configured = "/" + configured;
        }
        while (configured.endsWith("/") && configured.length() > 1) {
            configured = configured.substring(0, configured.length() - 1);
        }
        return configured;
    }

    private record ContentResponse(String id, String type, String title, Version version, Body body, Space space) {}

    private record Version(int number) {}

    private record Body(Storage storage) {}

    private record Storage(String value, String representation) {}

    private record AttachmentResponse(List<AttachmentResult> results) {}

    private record AttachmentResult(String id, String title) {}

    private record ChildPagesResponse(List<ChildPageResult> results) {}

    private record ChildPageResult(String id, String title) {}

    private record UpdateRequest(String id, String type, String title, Version version, Body body) {}

    private record CreateRequest(String title, String type, Space space, List<Ancestor> ancestors, Body body) {}

    private record Space(String key) {}

    private record Ancestor(String id) {}

    private List<ConfluencePageDescendant> loadDescendantsFromDescendantEndpoint(
            ConfluenceCredentials credentials, String rootPageId, HttpHeaders headers) {
        URI uri = apiUriBuilder()
                .pathSegment("content", rootPageId, "descendant", "page")
                .queryParam("expand", "ancestors")
                .queryParam("limit", 1000)
                .build(true)
                .toUri();
        return executeDescendantsRequest(
                credentials,
                uri,
                headers,
                "rootPageId=%s, expand=ancestors, limit=1000".formatted(rootPageId),
                "Failed to load Confluence descendant pages for " + rootPageId);
    }

    private List<ConfluencePageDescendant> loadDescendantsFromSearchEndpoint(
            ConfluenceCredentials credentials,
            String rootPageId,
            HttpHeaders headers,
            ConfluenceClientException originalError) {
        String cql = UriUtils.encodeQueryParam(
                "ancestor=" + rootPageId + " and type=page",
                StandardCharsets.UTF_8);
        URI uri = apiUriBuilder()
                .pathSegment("content", "search")
                .queryParam("cql", cql)
                .queryParam("expand", "ancestors")
                .queryParam("limit", 1000)
                .build(true)
                .toUri();
        try {
            return executeDescendantsRequest(
                    credentials,
                    uri,
                    headers,
                    "rootPageId=%s, cql=ancestor=%s and type=page, expand=ancestors, limit=1000"
                            .formatted(rootPageId, rootPageId),
                    "Failed to load Confluence descendant pages for " + rootPageId + " via search");
        } catch (ConfluenceClientException fallbackError) {
            throw new ConfluenceClientException(
                    fallbackError.getMessage() + " (primary error: " + originalError.getMessage() + ")",
                    fallbackError);
        }
    }

    private List<ConfluencePageDescendant> executeDescendantsRequest(
            ConfluenceCredentials credentials,
            URI uri,
            HttpHeaders headers,
            String requestContext,
            String errorPrefix) {
        logConfluenceRequest("GET", uri, headers, requestContext);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            return parseDescendantPages(response.getBody());
        } catch (RestClientException ex) {
            throw new ConfluenceClientException(buildErrorMessage(errorPrefix, ex), ex);
        }
    }

    private List<ConfluencePageDescendant> parseDescendantPages(JsonNode body) {
        if (body == null || body.isNull()) {
            return List.of();
        }

        JsonNode resultsNode = extractResultsNode(body);
        if (resultsNode == null || resultsNode.isNull() || !resultsNode.isArray()) {
            if (body.path("size").asInt(0) == 0) {
                return List.of();
            }
            throw new ConfluenceClientException("Confluence returned an unsupported descendant pages response format");
        }

        List<ConfluencePageDescendant> descendants = new ArrayList<>();
        for (JsonNode pageNode : resultsNode) {
            String pageId = normalizeJsonText(pageNode.path("id"));
            if (!StringUtils.hasText(pageId)) {
                continue;
            }
            String pageTitle = extractPageTitle(pageNode);
            descendants.add(new ConfluencePageDescendant(
                    pageId,
                    StringUtils.hasText(pageTitle) ? pageTitle : pageId,
                    extractAncestorIds(pageNode.path("ancestors"))));
        }
        return List.copyOf(descendants);
    }

    private JsonNode extractResultsNode(JsonNode body) {
        JsonNode directResults = body.path("results");
        if (directResults.isArray()) {
            return directResults;
        }

        JsonNode pagedPageResults = body.path("page").path("results");
        if (pagedPageResults.isArray()) {
            return pagedPageResults;
        }

        return null;
    }

    private List<String> extractAncestorIds(JsonNode ancestorsNode) {
        if (ancestorsNode == null || !ancestorsNode.isArray()) {
            return List.of();
        }

        List<String> ancestorIds = new ArrayList<>();
        for (JsonNode ancestorNode : ancestorsNode) {
            String ancestorId = normalizeJsonText(ancestorNode.path("id"));
            if (StringUtils.hasText(ancestorId)) {
                ancestorIds.add(ancestorId);
            }
        }
        return List.copyOf(ancestorIds);
    }

    private String extractPageTitle(JsonNode pageNode) {
        String directTitle = normalizeJsonText(pageNode.path("title"));
        if (StringUtils.hasText(directTitle)) {
            return directTitle;
        }

        String displayTitle = normalizeJsonText(pageNode.path("displayTitle"));
        if (StringUtils.hasText(displayTitle)) {
            return displayTitle;
        }

        String nestedContentTitle = normalizeJsonText(pageNode.path("content").path("title"));
        if (StringUtils.hasText(nestedContentTitle)) {
            return nestedContentTitle;
        }

        return normalizeJsonText(pageNode.path("page").path("title"));
    }

    private String normalizeJsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private void logConfluenceRequest(String method, URI uri, HttpHeaders headers, String context) {
        log.info(
                "Confluence request: {} {} headers={} {}",
                method,
                uri,
                headers.toSingleValueMap(),
                context != null ? context : "");
    }
}
