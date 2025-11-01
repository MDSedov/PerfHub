package com.example.chronicler.controller;

import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.service.ConfluenceService;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
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

    private final ConfluenceService confluenceService;

    public ConfluenceController(ConfluenceService confluenceService) {
        this.confluenceService = confluenceService;
    }

    @PatchMapping(path = "/report/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReportResponse> publishReport(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody ConfluenceReportRequest request) {
        ConfluenceCredentials credentials = new ConfluenceCredentials(login, token);
        ConfluenceReportResponse response = confluenceService.publishLatestGrafanaRender(credentials, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/report/hierarchy/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfluenceReportHierarchyResponse> createReportHierarchy(
            @RequestHeader(value = LOGIN_HEADER, required = false) String login,
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody ConfluenceReportHierarchyRequest request) {
        ConfluenceCredentials credentials = new ConfluenceCredentials(login, token);
        ConfluenceReportHierarchyResponse response =
                confluenceService.createReportHierarchy(credentials, request.parentPageId());
        return ResponseEntity.ok(response);
    }
}
