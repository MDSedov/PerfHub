package com.example.chronicler.controller;

import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.service.VictoriaMetricsService;
import jakarta.validation.Valid;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/vm", produces = MediaType.APPLICATION_JSON_VALUE)
public class VictoriaMetricsController {

    private final VictoriaMetricsService victoriaMetricsService;

    VictoriaMetricsController(VictoriaMetricsService victoriaMetricsService) {
        this.victoriaMetricsService = victoriaMetricsService;
    }

    @PostMapping(path = "/data/", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> fetchMetric(@RequestBody List<@Valid VictoriaMetricsRequest> requests) {
        String body = victoriaMetricsService.fetchMetrics(requests);
        return ResponseEntity.ok(body);
    }
}
