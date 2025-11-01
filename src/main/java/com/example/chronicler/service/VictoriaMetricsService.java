package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.exception.VictoriaMetricsResponseProcessingException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.registry.VmQueryRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class VictoriaMetricsService {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DASHBOARD_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final VmQueryRegistry queryRegistry;
    private final VictoriaMetricsClient client;
    private final VmDataCache vmDataCache;
    private final ObjectMapper objectMapper;

    /** Конструктор подготавливает зависимости сервиса VictoriaMetrics. */
    VictoriaMetricsService(
            VmQueryRegistry queryRegistry,
            VictoriaMetricsClient client,
            VmDataCache vmDataCache,
            ObjectMapper objectMapper) {
        this.queryRegistry = queryRegistry;
        this.client = client;
        this.vmDataCache = vmDataCache;
        this.objectMapper = objectMapper;
    }

    /** Метод собирает данные VictoriaMetrics по списку запросов и кеширует результат. */
    public String fetchMetrics(List<VictoriaMetricsRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidTimeRangeException("At least one VictoriaMetrics request must be provided");
        }

        Instant commonStart = null;
        Instant commonEnd = null;
        List<String> types = new ArrayList<>(requests.size());
        ArrayNode resultsArray = objectMapper.createArrayNode();

        for (VictoriaMetricsRequest request : requests) {
            Objects.requireNonNull(request, "VictoriaMetrics request must not be null");

            Instant start = parseInstant(request.start(), "start");
            Instant end = parseInstant(request.end(), "end");

            if (!end.isAfter(start)) {
                throw new InvalidTimeRangeException("End time must be after start time");
            }

            if (commonStart == null) {
                commonStart = start;
                commonEnd = end;
            } else if (!commonStart.equals(start) || !commonEnd.equals(end)) {
                throw new InvalidTimeRangeException("All VictoriaMetrics requests must have the same start and end");
            }

            String type = request.type();
            String query = queryRegistry
                    .findQuery(type)
                    .orElseThrow(() -> new VmQueryNotFoundException(type));

            String response = client.queryRange(query, start, end);
            types.add(type);
            resultsArray.add(createResultNode(type, response));
        }

        ObjectNode aggregated = objectMapper.createObjectNode();
        aggregated.put("start", Objects.requireNonNull(commonStart).toString());
        aggregated.put("end", Objects.requireNonNull(commonEnd).toString());
        aggregated.set("results", resultsArray);

        String payload = writeAggregated(aggregated);
        vmDataCache.store(types, commonStart, commonEnd, payload);
        return payload;
    }

    /** Метод преобразует время из формата Grafana (МСК) в Instant. */
    private Instant parseInstant(String value, String field) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(value, DASHBOARD_FORMAT);
            return localDateTime.atZone(MOSCOW_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            throw new InvalidTimeRangeException(
                    "Unable to parse " + field + " as 'yyyy-MM-dd HH:mm:ss' in Europe/Moscow timezone");
        }
    }

    /** Метод формирует узел результата с типом и данными. */
    private ObjectNode createResultNode(String type, String response) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("type", type);
        resultNode.set("data", readResponse(response));
        return resultNode;
    }

    /** Метод преобразует строковый ответ VictoriaMetrics в JSON. */
    private JsonNode readResponse(String response) {
        if (response == null || response.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(response);
        } catch (JsonProcessingException ex) {
            throw new VictoriaMetricsResponseProcessingException(
                    "Failed to parse VictoriaMetrics response as JSON", ex);
        }
    }

    /** Метод сериализует агрегированные данные в строку JSON. */
    private String writeAggregated(ObjectNode aggregated) {
        try {
            return objectMapper.writeValueAsString(aggregated);
        } catch (JsonProcessingException ex) {
            throw new VictoriaMetricsResponseProcessingException(
                    "Failed to serialize aggregated VictoriaMetrics response", ex);
        }
    }
}
