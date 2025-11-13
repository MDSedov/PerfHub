package com.example.chronicler.controller.settings;

import com.example.chronicler.dto.settings.VictoriaMetricsQueryDto;
import com.example.chronicler.dto.settings.VictoriaMetricsQueryRequest;
import com.example.chronicler.model.VictoriaMetricsQuery;
import com.example.chronicler.service.VictoriaMetricsQuerySettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
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
        path = "/api/settings/victoria-metrics-queries",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class VictoriaMetricsQuerySettingsController {

    private final VictoriaMetricsQuerySettingsService service;

    public VictoriaMetricsQuerySettingsController(VictoriaMetricsQuerySettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<VictoriaMetricsQueryDto> listQueries() {
        return service.listQueries().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public VictoriaMetricsQueryDto createQuery(@Valid @RequestBody VictoriaMetricsQueryRequest request) {
        return toDto(service.create(request.code(), request.expression(), request.description()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VictoriaMetricsQueryDto updateQuery(
            @PathVariable long id,
            @Valid @RequestBody VictoriaMetricsQueryRequest request) {
        return toDto(service.update(id, request.code(), request.expression(), request.description()));
    }

    @DeleteMapping("/{id}")
    public void deleteQuery(@PathVariable long id) {
        service.delete(id);
    }

    private VictoriaMetricsQueryDto toDto(VictoriaMetricsQuery query) {
        return new VictoriaMetricsQueryDto(query.id(), query.code(), query.expression(), query.description());
    }
}
