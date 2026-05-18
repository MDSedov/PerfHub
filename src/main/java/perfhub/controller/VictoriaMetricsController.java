package perfhub.controller;

import perfhub.dto.VictoriaMetricsRequest;
import perfhub.dto.VictoriaMetricsTestsRequest;
import perfhub.service.VictoriaMetricsService;
import jakarta.validation.Valid;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/victoriametrics", produces = MediaType.APPLICATION_JSON_VALUE)
public class VictoriaMetricsController {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsController.class);

    private final VictoriaMetricsService victoriaMetricsService;

    /** Конструктор сохраняет ссылку на сервис VictoriaMetrics. */
    VictoriaMetricsController(VictoriaMetricsService victoriaMetricsService) {
        this.victoriaMetricsService = victoriaMetricsService;
    }

    /** Метод получает запросы к VictoriaMetrics и возвращает агрегированные данные. */
    @PostMapping(path = "/data/", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> fetchMetric(@RequestBody List<@Valid VictoriaMetricsRequest> requests) {
        String body = victoriaMetricsService.fetchMetrics(requests);
        return ResponseEntity.ok(body);
    }

    @PostMapping(path = "/tests/", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> fetchTests(@Valid @RequestBody VictoriaMetricsTestsRequest request) {
        log.info(
                "Received request to detect load tests in VictoriaMetrics for range {} - {}",
                request.start(),
                request.end());
        String body = victoriaMetricsService.detectLoadTests(request.start(), request.end());
        return ResponseEntity.ok(body);
    }
}
