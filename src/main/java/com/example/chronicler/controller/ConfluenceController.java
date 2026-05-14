package com.example.chronicler.controller;

import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendRequest;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendResponse;
import com.example.chronicler.dto.confluence.ConfluencePageTreeNode;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateResponse;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.service.ConfluenceService;
import com.example.chronicler.service.ConfigurationProvider;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/confluence", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfluenceController {

    public static final String LOGIN_HEADER = "X-Confluence-Login";
    public static final String TOKEN_HEADER = "X-Confluence-Token";
    private static final String CONFLUENCE_SERVICE = "confluence";
    private static final String PERSONAL_ACCESS_TOKEN_KEY = "personal-access-token";

    private final ConfluenceService confluenceService;
    private final ConfigurationProvider configurationProvider;

    public ConfluenceController(ConfluenceService confluenceService, ConfigurationProvider configurationProvider) {
        this.confluenceService = confluenceService;
        this.configurationProvider = configurationProvider;
    }

    @PostMapping(path = "/report/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReportResponse> publishReport(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ConfluenceReportRequest request) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluenceReportResponse response = confluenceService.createReports(credentials, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/report/hierarchy/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReportHierarchyResponse> createReportHierarchy(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ConfluenceReportHierarchyRequest request) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluenceReportHierarchyResponse response =
                confluenceService.createReportHierarchy(credentials, request.parentPageId());
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/report/parent-pages/")
    public ResponseEntity<ConfluencePageTreeNode> getReportParentPages(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluencePageTreeNode response = confluenceService.getReportParentPageTree(credentials);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(path = "/report/release-page/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReleaseReportPageResponse> createReleaseReportPage(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ConfluenceReleaseReportPageRequest request) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluenceReleaseReportPageResponse response =
                confluenceService.createReleaseReportPage(credentials, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/report/release-page/links/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReleaseReportLinksUpdateResponse> updateReleaseReportLinks(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ConfluenceReleaseReportLinksUpdateRequest request) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluenceReleaseReportLinksUpdateResponse response =
                confluenceService.updateReleaseReportLinks(credentials, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/charts/append/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceChartsAppendResponse> appendCharts(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ConfluenceChartsAppendRequest request) {
        String resolvedLogin = resolveLogin(login);
        String resolvedToken = resolveToken(token);
        ConfluenceCredentials credentials = new ConfluenceCredentials(resolvedLogin, resolvedToken);
        ConfluenceChartsAppendResponse response = confluenceService.appendChartsToPage(credentials, request);
        return ResponseEntity.ok(response);
    }

    private String resolveLogin(String headerLogin) {
        if (headerLogin != null && !headerLogin.isBlank()) {
            return headerLogin;
        }
        return configurationProvider.getRequiredValue(CONFLUENCE_SERVICE, PERSONAL_ACCESS_TOKEN_KEY);
    }

    private String resolveToken(String headerToken) {
        return configurationProvider.getRequiredValue(CONFLUENCE_SERVICE, PERSONAL_ACCESS_TOKEN_KEY);
    }
}
