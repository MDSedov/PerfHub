package com.example.chronicler.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.chronicler.dto.jira.JiraReleaseDistribution;
import com.example.chronicler.dto.jira.JiraReleaseIssue;
import com.example.chronicler.exception.JiraClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);
    private static final Pattern JSON_MESSAGE_PATTERN =
            Pattern.compile("\"(?:message|error|errorMessages)\"\\s*:\\s*(\"([^\"]+)\"|\\[\"([^\"]+)\"\\])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9]+-\\d+");
    private static final Pattern HTML_TABLE_PATTERN = Pattern.compile("(?is)<table\\b[^>]*>.*?</table>");
    private static final Pattern HTML_TABLE_ROW_PATTERN = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern HTML_TABLE_CELL_PATTERN = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>");
    private static final Pattern HTML_LINK_PATTERN =
            Pattern.compile("(?is)<a\\b[^>]*\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[([^|\\]]+)\\|([^\\]]+)]");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]|<>\"]+");
    private static final String INFRASTRUCTURE_INSTALL_SECTION_TITLE = "Установка на НТ";
    private static final String SERVICE = "jira";
    private static final String BASE_URL_KEY = "base-url";
    private static final String API_PATH_KEY = "api-path";
    private static final String TOKEN_KEY = "personal-access-token";
    private static final String PROJECT_NAME_KEY = "project-name";
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

    public boolean isConfiguredForReleaseLookup() {
        return hasConfiguredValue(BASE_URL_KEY)
                && hasConfiguredValue(TOKEN_KEY)
                && hasConfiguredValue(PROJECT_NAME_KEY);
    }

    public Optional<JiraReleaseIssue> findReleaseIssueByRelease(String release) {
        String normalizedRelease = normalizeText(release);
        if (!StringUtils.hasText(normalizedRelease)) {
            return Optional.empty();
        }
        String projectName = required(PROJECT_NAME_KEY).trim();
        URI uri = apiUriBuilder()
                .pathSegment("project", projectName, "versions")
                .build()
                .encode()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(TOKEN_KEY));

        try {
            ResponseEntity<JiraVersion[]> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JiraVersion[].class);
            JiraVersion[] versions = response.getBody();
            Optional<JiraReleaseIssue> versionIssue = findReleaseIssueInVersions(normalizedRelease, versions);
            if (versionIssue.isPresent()) {
                return versionIssue;
            }
        } catch (RestClientException ex) {
            log.debug("Failed to load Jira project versions, falling back to search API", ex);
        }

        try {
            return findReleaseIssueBySearch(normalizedRelease, projectName, headers);
        } catch (RestClientException ex) {
            throw new JiraClientException(buildErrorMessage("Failed to find Jira release issue", ex), ex);
        } catch (IllegalArgumentException ex) {
            throw new JiraClientException("Invalid Jira connection configuration: " + ex.getMessage(), ex);
        }
    }

    public List<JiraReleaseDistribution> findReleaseDistributionsByRelease(String release) {
        String normalizedRelease = normalizeText(release);
        if (!StringUtils.hasText(normalizedRelease)) {
            return List.of();
        }
        String projectName = required(PROJECT_NAME_KEY).trim();
        List<String> fixVersionCandidates = buildFixVersionCandidates(normalizedRelease);
        if (fixVersionCandidates.isEmpty()) {
            return List.of();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        headers.setBearerAuth(required(TOKEN_KEY));

        String versions = fixVersionCandidates.stream()
                .map(candidate -> "\"" + escapeJql(candidate) + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElse("\"" + escapeJql(normalizedRelease) + "\"");
        String jql = "project = \"%s\" AND fixVersion in (%s)"
                .formatted(escapeJql(projectName), versions);
        URI uri = apiUriBuilder()
                .pathSegment("search")
                .queryParam("jql", jql)
                .queryParam("fields", "summary,description,fixVersions")
                .queryParam("expand", "renderedFields")
                .queryParam("maxResults", 50)
                .build()
                .encode()
                .toUri();

        try {
            ResponseEntity<JiraSearchResponse> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JiraSearchResponse.class);
            JiraSearchResponse body = response.getBody();
            if (body == null || body.issues() == null || body.issues().isEmpty()) {
                return List.of();
            }
            return body.issues().stream()
                    .filter(issue -> issue != null)
                    .map(this::extractReleaseDistributions)
                    .filter(distributions -> !distributions.isEmpty())
                    .findFirst()
                    .orElseGet(List::of);
        } catch (RestClientException ex) {
            throw new JiraClientException(buildErrorMessage("Failed to find Jira release distributions", ex), ex);
        } catch (IllegalArgumentException ex) {
            throw new JiraClientException("Invalid Jira connection configuration: " + ex.getMessage(), ex);
        }
    }

    private Optional<JiraReleaseIssue> findReleaseIssueInVersions(String normalizedRelease, JiraVersion[] versions) {
        if (versions == null || versions.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(versions)
                .filter(version -> version != null)
                .filter(version -> normalizedRelease.equals(normalizeText(version.description())))
                .map(JiraVersion::name)
                .map(this::normalizeText)
                .filter(this::looksLikeIssueKey)
                .findFirst()
                .map(key -> new JiraReleaseIssue(key, buildIssueUrl(key)));
    }

    private Optional<JiraReleaseIssue> findReleaseIssueBySearch(
            String normalizedRelease,
            String projectName,
            HttpHeaders headers) {
        String jql = "project = \"%s\" AND text ~ \"%s\""
                .formatted(escapeJql(projectName), escapeJql(normalizedRelease));
        URI uri = apiUriBuilder()
                .pathSegment("search")
                .queryParam("jql", jql)
                .queryParam("fields", "summary,description")
                .queryParam("maxResults", 50)
                .build()
                .encode()
                .toUri();

        ResponseEntity<JiraSearchResponse> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JiraSearchResponse.class);
        JiraSearchResponse body = response.getBody();
        if (body == null || body.issues() == null || body.issues().isEmpty()) {
            return Optional.empty();
        }

        return body.issues().stream()
                .filter(issue -> issue != null)
                .filter(issue -> looksLikeIssueKey(normalizeText(issue.key())))
                .filter(issue -> issueContainsRelease(issue, normalizedRelease))
                .map(issue -> normalizeText(issue.key()))
                .findFirst()
                .map(key -> new JiraReleaseIssue(key, buildIssueUrl(key)));
    }

    private List<String> buildFixVersionCandidates(String normalizedRelease) {
        if (!StringUtils.hasText(normalizedRelease)) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedRelease);
        String[] parts = normalizedRelease.split("\\.");
        if (parts.length >= 3 && trailingVersionPartsAreZero(parts, 2)
                && StringUtils.hasText(parts[0]) && StringUtils.hasText(parts[1])) {
            candidates.add(parts[0] + "." + parts[1]);
        }
        return candidates.stream().toList();
    }

    private boolean trailingVersionPartsAreZero(String[] parts, int fromIndex) {
        for (int index = fromIndex; index < parts.length; index++) {
            if (!parts[index].matches("0+")) {
                return false;
            }
        }
        return true;
    }

    private List<JiraReleaseDistribution> extractReleaseDistributions(JiraIssue issue) {
        List<JiraReleaseDistribution> renderedDistributions = parseRenderedDistributionTable(
                extractJsonText(issue.renderedFields() != null ? issue.renderedFields().description() : null));
        if (!renderedDistributions.isEmpty()) {
            return renderedDistributions;
        }
        JiraIssueFields fields = issue.fields();
        return parseWikiDistributionTable(fields != null ? extractJsonText(fields.description()) : null);
    }

    private List<JiraReleaseDistribution> parseRenderedDistributionTable(String renderedDescription) {
        if (!StringUtils.hasText(renderedDescription)) {
            return List.of();
        }
        String html = HtmlUtils.htmlUnescape(renderedDescription);
        int sectionIndex = indexOfIgnoreCase(html, INFRASTRUCTURE_INSTALL_SECTION_TITLE);
        if (sectionIndex < 0) {
            return List.of();
        }
        Matcher tableMatcher = HTML_TABLE_PATTERN.matcher(html);
        if (!tableMatcher.find(sectionIndex)) {
            return List.of();
        }
        return normalizeDistributions(parseHtmlDistributionRows(tableMatcher.group()));
    }

    private List<JiraReleaseDistribution> parseHtmlDistributionRows(String tableHtml) {
        List<JiraReleaseDistribution> result = new ArrayList<>();
        Matcher rowMatcher = HTML_TABLE_ROW_PATTERN.matcher(tableHtml);
        while (rowMatcher.find()) {
            List<String> cells = extractHtmlCells(rowMatcher.group(1));
            if (cells.size() < 2) {
                continue;
            }
            JiraReleaseDistribution distribution = buildHtmlDistribution(cells);
            if (distribution != null) {
                result.add(distribution);
            }
        }
        return result;
    }

    private List<String> extractHtmlCells(String rowHtml) {
        List<String> cells = new ArrayList<>();
        Matcher cellMatcher = HTML_TABLE_CELL_PATTERN.matcher(rowHtml);
        while (cellMatcher.find()) {
            cells.add(cellMatcher.group(1));
        }
        return cells;
    }

    private JiraReleaseDistribution buildHtmlDistribution(List<String> cells) {
        String serviceName = stripHtml(cells.get(0));
        DistributionLink link = findHtmlDistributionLink(cells.subList(1, cells.size()));
        String distributionName = link != null ? link.text() : firstNonBlankText(cells.subList(1, cells.size()));
        String distributionUrl = link != null ? link.url() : findPlainUrl(distributionName);
        if (isDistributionHeader(serviceName, distributionName)) {
            return null;
        }
        return buildDistribution(serviceName, distributionName, distributionUrl);
    }

    private DistributionLink findHtmlDistributionLink(List<String> cells) {
        for (String cell : cells) {
            Matcher linkMatcher = HTML_LINK_PATTERN.matcher(cell);
            if (linkMatcher.find()) {
                String url = normalizeText(HtmlUtils.htmlUnescape(linkMatcher.group(2)));
                String text = normalizeText(stripHtml(linkMatcher.group(3)));
                if (!StringUtils.hasText(text)) {
                    text = lastUrlSegment(url);
                }
                if (StringUtils.hasText(text) || StringUtils.hasText(url)) {
                    return new DistributionLink(text, url);
                }
            }
            String text = stripHtml(cell);
            String url = findPlainUrl(text);
            if (StringUtils.hasText(url)) {
                String name = normalizeText(text.replace(url, ""));
                return new DistributionLink(StringUtils.hasText(name) ? name : lastUrlSegment(url), url);
            }
        }
        return null;
    }

    private List<JiraReleaseDistribution> parseWikiDistributionTable(String rawDescription) {
        String section = extractTextSection(rawDescription, INFRASTRUCTURE_INSTALL_SECTION_TITLE);
        if (!StringUtils.hasText(section)) {
            return List.of();
        }
        List<JiraReleaseDistribution> result = new ArrayList<>();
        boolean tableStarted = false;
        for (String line : section.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                if (tableStarted && StringUtils.hasText(trimmed)) {
                    break;
                }
                continue;
            }
            tableStarted = true;
            if (trimmed.startsWith("||")) {
                continue;
            }
            List<String> cells = parseWikiRowCells(trimmed);
            if (cells.size() < 2) {
                continue;
            }
            JiraReleaseDistribution distribution = buildWikiDistribution(cells);
            if (distribution != null) {
                result.add(distribution);
            }
        }
        return normalizeDistributions(result);
    }

    private List<String> parseWikiRowCells(String row) {
        String normalizedRow = row;
        while (normalizedRow.startsWith("|")) {
            normalizedRow = normalizedRow.substring(1);
        }
        while (normalizedRow.endsWith("|")) {
            normalizedRow = normalizedRow.substring(0, normalizedRow.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int bracketDepth = 0;
        for (int index = 0; index < normalizedRow.length(); index++) {
            char current = normalizedRow.charAt(index);
            if (current == '[') {
                bracketDepth++;
            } else if (current == ']' && bracketDepth > 0) {
                bracketDepth--;
            }
            if (current == '|' && bracketDepth == 0) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private JiraReleaseDistribution buildWikiDistribution(List<String> cells) {
        String serviceName = normalizeText(cells.get(0));
        DistributionLink link = findWikiDistributionLink(cells.subList(1, cells.size()));
        String distributionName = link != null ? link.text() : firstNonBlank(cells.subList(1, cells.size()));
        String distributionUrl = link != null ? link.url() : findPlainUrl(distributionName);
        if (isDistributionHeader(serviceName, distributionName)) {
            return null;
        }
        return buildDistribution(serviceName, distributionName, distributionUrl);
    }

    private DistributionLink findWikiDistributionLink(List<String> cells) {
        for (String cell : cells) {
            Matcher markdownMatcher = MARKDOWN_LINK_PATTERN.matcher(cell);
            if (markdownMatcher.find()) {
                return new DistributionLink(
                        normalizeText(markdownMatcher.group(1)),
                        normalizeText(markdownMatcher.group(2)));
            }
            Matcher wikiMatcher = WIKI_LINK_PATTERN.matcher(cell);
            if (wikiMatcher.find()) {
                return new DistributionLink(
                        normalizeText(wikiMatcher.group(1)),
                        normalizeText(wikiMatcher.group(2)));
            }
            String url = findPlainUrl(cell);
            if (StringUtils.hasText(url)) {
                String name = normalizeText(cell.replace(url, ""));
                return new DistributionLink(StringUtils.hasText(name) ? name : lastUrlSegment(url), url);
            }
        }
        return null;
    }

    private JiraReleaseDistribution buildDistribution(String serviceName, String distributionName, String distributionUrl) {
        String service = normalizeText(serviceName);
        String name = normalizeText(distributionName);
        String url = normalizeText(distributionUrl);
        if (!StringUtils.hasText(service) || (!StringUtils.hasText(name) && !StringUtils.hasText(url))) {
            return null;
        }
        if (!StringUtils.hasText(name)) {
            name = lastUrlSegment(url);
        }
        return new JiraReleaseDistribution(service, name, url);
    }

    private List<JiraReleaseDistribution> normalizeDistributions(List<JiraReleaseDistribution> distributions) {
        if (distributions == null || distributions.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<JiraReleaseDistribution> result = new ArrayList<>();
        for (JiraReleaseDistribution distribution : distributions) {
            if (distribution == null) {
                continue;
            }
            JiraReleaseDistribution normalized = buildDistribution(
                    distribution.serviceName(),
                    distribution.distributionName(),
                    distribution.distributionUrl());
            if (normalized == null) {
                continue;
            }
            String key = "%s\u0000%s\u0000%s".formatted(
                    normalized.serviceName(),
                    normalized.distributionName(),
                    normalized.distributionUrl());
            if (seen.add(key)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String firstNonBlankText(List<String> htmlCells) {
        for (String cell : htmlCells) {
            String text = stripHtml(cell);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String firstNonBlank(List<String> values) {
        for (String value : values) {
            String normalized = normalizeText(value);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String stripHtml(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return normalizeMessage(HtmlUtils.htmlUnescape(value.replaceAll("(?is)<[^>]+>", " ")));
    }

    private String extractTextSection(String text, String title) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(title)) {
            return null;
        }
        int titleIndex = indexOfIgnoreCase(text, title);
        if (titleIndex < 0) {
            return null;
        }
        return text.substring(titleIndex + title.length());
    }

    private int indexOfIgnoreCase(String value, String search) {
        if (value == null || search == null) {
            return -1;
        }
        return value.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT));
    }

    private boolean isDistributionHeader(String serviceName, String distributionName) {
        String service = normalizeMessage(serviceName).toLowerCase(Locale.ROOT);
        String distribution = normalizeMessage(distributionName).toLowerCase(Locale.ROOT);
        return service.contains("сервис")
                && (distribution.contains("дистрибутив")
                || distribution.contains("nexus")
                || distribution.contains("ссылка"));
    }

    private String findPlainUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(value);
        return matcher.find() ? normalizeText(matcher.group()) : null;
    }

    private String lastUrlSegment(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String normalizedUrl = url.trim();
        int queryIndex = normalizedUrl.indexOf('?');
        if (queryIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, queryIndex);
        }
        while (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        int slashIndex = normalizedUrl.lastIndexOf('/');
        return slashIndex >= 0 ? normalizedUrl.substring(slashIndex + 1) : normalizedUrl;
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

    private boolean hasConfiguredValue(String key) {
        return configurationProvider.findValue(SERVICE, key)
                .map(this::normalizeText)
                .filter(StringUtils::hasText)
                .isPresent();
    }

    private String buildIssueUrl(String key) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl())
                .pathSegment("browse", key)
                .build()
                .encode()
                .toUriString();
    }

    private boolean looksLikeIssueKey(String value) {
        return StringUtils.hasText(value) && ISSUE_KEY_PATTERN.matcher(value).matches();
    }

    private boolean issueContainsRelease(JiraIssue issue, String normalizedRelease) {
        JiraIssueFields fields = issue.fields();
        if (fields == null) {
            return false;
        }
        String description = normalizeMessage(extractJsonText(fields.description()));
        String summary = normalizeMessage(extractJsonText(fields.summary()));
        return containsRelease(description, normalizedRelease) || containsRelease(summary, normalizedRelease);
    }

    private boolean containsRelease(String value, String normalizedRelease) {
        return StringUtils.hasText(value)
                && (value.equals(normalizedRelease) || value.contains(normalizedRelease));
    }

    private String extractJsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        appendJsonText(node, builder);
        return builder.toString();
    }

    private void appendJsonText(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isValueNode()) {
            String value = node.asText(null);
            if (StringUtils.hasText(value)) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value);
            }
            return;
        }
        node.elements().forEachRemaining(child -> appendJsonText(child, builder));
    }

    private String escapeJql(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JiraVersion(String name, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JiraSearchResponse(List<JiraIssue> issues) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JiraIssue(String key, JiraIssueFields fields, JiraRenderedIssueFields renderedFields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JiraIssueFields(JsonNode summary, JsonNode description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JiraRenderedIssueFields(JsonNode description) {}

    private record DistributionLink(String text, String url) {}
}
