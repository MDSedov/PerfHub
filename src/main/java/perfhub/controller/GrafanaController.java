package perfhub.controller;

import perfhub.dto.grafana.GrafanaRenderRequest;
import perfhub.dto.grafana.GrafanaRenderPanelRangeRequest;
import perfhub.dto.grafana.GrafanaRenderResponse;
import perfhub.dto.grafana.GrafanaRenderRangeRequest;
import perfhub.dto.grafana.GrafanaRenderedPanel;
import perfhub.service.GrafanaService;
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

    @PostMapping(path = "/panels/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GrafanaRenderResponse> renderPanels(
            @Valid @RequestBody GrafanaRenderRequest request) {
        GrafanaRenderResponse response = grafanaService.renderPanels(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/panels/range", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GrafanaRenderResponse> renderPanelsByRange(
            @Valid @RequestBody GrafanaRenderRangeRequest request) {
        GrafanaRenderResponse response = grafanaService.renderPanelsByRange(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/panels/range/panel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GrafanaRenderedPanel> renderPanelByRange(
            @Valid @RequestBody GrafanaRenderPanelRangeRequest request) {
        GrafanaRenderedPanel response = grafanaService.renderPanelByRange(request);
        return ResponseEntity.ok(response);
    }
}
