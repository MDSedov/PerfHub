package com.example.chronicler.controller;

import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.service.VictoriaMetricsService;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import jakarta.validation.Valid;

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
    ResponseEntity<String> fetchMetric(@Valid @RequestBody VictoriaMetricsRequest request) {
        Instant start = parseInstant(request.start(), "start");
        Instant end = parseInstant(request.end(), "end");
        String body = victoriaMetricsService.fetchMetric(request.type(), start, end);
        return ResponseEntity.ok(body);
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidTimeRangeException("Unable to parse " + field + " as ISO-8601 instant");
        }
    }
}
