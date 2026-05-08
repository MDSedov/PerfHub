package com.example.chronicler.controller.settings;

import com.example.chronicler.dto.settings.GrafanaDashboardCreateRequest;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardDto;
import com.example.chronicler.dto.settings.GrafanaDashboardUpdateRequest;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.service.GrafanaDashboardSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        path = "/api/settings/grafana-dashboards",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaDashboardSettingsController {

    private final GrafanaDashboardSettingsService service;

    public GrafanaDashboardSettingsController(GrafanaDashboardSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<GrafanaDashboardDetailsDto> listDashboards() {
        return service.listDashboards();
    }

    @GetMapping("/discover")
    public List<GrafanaDashboardDetailsDto> discoverDashboards() {
        return service.pullFromGrafana();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public GrafanaDashboardDto createDashboard(@Valid @RequestBody GrafanaDashboardCreateRequest request) {
        GrafanaDashboard dashboard = service.create(
                request.uid(), request.slug(), request.panels(), request.enabled());
        return toDto(dashboard);
    }

    @PutMapping(path = "/{uid}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GrafanaDashboardDto updateDashboard(
            @PathVariable("uid") String uid,
            @Valid @RequestBody GrafanaDashboardUpdateRequest request) {
        GrafanaDashboard dashboard = service.update(
                uid,
                request.slug(),
                request.title(),
                request.panels(),
                request.enabled(),
                request.panelSettings(),
                request.variables());
        return toDto(dashboard);
    }

    @DeleteMapping("/{uid}")
    public void deleteDashboard(@PathVariable("uid") String uid) {
        service.delete(uid);
    }

    private GrafanaDashboardDto toDto(GrafanaDashboard dashboard) {
        return new GrafanaDashboardDto(dashboard.uid(), dashboard.slug(), dashboard.panels(), dashboard.enabled());
    }
}
