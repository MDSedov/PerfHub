package com.example.chronicler.controller;

import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.service.GrafanaService;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/grafana", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaController {

    private final GrafanaService grafanaService;

    public GrafanaController(GrafanaService grafanaService) {
        this.grafanaService = grafanaService;
    }

    @PostMapping(path = "/render/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GrafanaRenderResponse> renderDashboards(
            @Valid @RequestBody GrafanaRenderRequest request) {
        GrafanaRenderResponse response = grafanaService.renderDashboards(request);
        return ResponseEntity.ok(response);
    }
}
