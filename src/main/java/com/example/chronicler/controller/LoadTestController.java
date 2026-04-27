package com.example.chronicler.controller;

import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.tests.LoadTestView;
import com.example.chronicler.dto.tests.RequirementComplianceView;
import com.example.chronicler.dto.tests.SaveTestCommentRequest;
import com.example.chronicler.dto.tests.SaveTestTypeRequest;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.service.KubernetesConfigurationService;
import com.example.chronicler.service.RequirementEvaluationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST-контроллер для работы со списком нагрузочных тестов. */
@RestController
@RequestMapping(path = "/api/tests", produces = MediaType.APPLICATION_JSON_VALUE)
public class LoadTestController {

    private final LoadTestRepository loadTestRepository;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final RequirementEvaluationService requirementEvaluationService;

    public LoadTestController(
            LoadTestRepository loadTestRepository,
            KubernetesConfigurationService kubernetesConfigurationService,
            RequirementEvaluationService requirementEvaluationService) {
        this.loadTestRepository = loadTestRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.requirementEvaluationService = requirementEvaluationService;
    }

    @GetMapping
    public List<LoadTestView> listTests() {
        List<LoadTest> tests = loadTestRepository.findAllOrderByStartDesc();
        List<UUID> testIds = tests.stream().map(LoadTest::id).collect(Collectors.toList());
        Map<UUID, String> releases = kubernetesConfigurationService.findMaxReleases(testIds);
        Set<UUID> configuredTests = kubernetesConfigurationService.findConfiguredTestIds(testIds);
        return tests.stream()
                .map(loadTest -> LoadTestView.fromModel(
                        loadTest,
                        buildComplianceView(loadTest),
                        releases.get(loadTest.id()),
                        configuredTests.contains(loadTest.id())))
                .collect(Collectors.toList());
    }

    @GetMapping("/{testUuid}/configuration")
    public DropAppConfigurationSnapshotDetailsDto getTestConfiguration(@PathVariable("testUuid") UUID testUuid) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        return kubernetesConfigurationService.getConfigurationDetails(testUuid);
    }

    /** Запрашивает и сохраняет текущую конфигурацию стенда для теста. */
    @PostMapping("/{testUuid}/configuration/capture")
    public DropAppConfigurationSnapshotDetailsDto captureConfiguration(@PathVariable("testUuid") UUID testUuid) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        return kubernetesConfigurationService.captureAndGetConfigurationDetails(testUuid);
    }

    /** Скачивает ZIP-архив конфигурации стенда, сохраненной для теста. */
    @GetMapping(path = "/{testUuid}/configuration/archive", produces = "application/zip")
    public ResponseEntity<byte[]> downloadConfigurationArchive(@PathVariable("testUuid") UUID testUuid) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        var archive = kubernetesConfigurationService.createConfigurationArchive(testUuid);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.fileName() + "\"")
                .contentType(MediaType.parseMediaType(archive.contentType()))
                .body(archive.content());
    }

    /** Сохраняет комментарий для теста и возвращает обновленную информацию. */
    @PostMapping("/{testUuid}/comment")
    public LoadTestView saveComment(
            @PathVariable("testUuid") UUID testUuid, @RequestBody SaveTestCommentRequest request) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        LoadTest updated = loadTestRepository.updateComment(testUuid, request != null ? request.comment() : null);
        return LoadTestView.fromModel(
                updated,
                buildComplianceView(updated),
                kubernetesConfigurationService.findMaxRelease(testUuid),
                kubernetesConfigurationService.hasConfiguration(testUuid));
    }

    /** Сохраняет тип теста и возвращает обновленную информацию. */
    @PostMapping("/{testUuid}/type")
    public LoadTestView saveType(@PathVariable("testUuid") UUID testUuid, @RequestBody SaveTestTypeRequest request) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        LoadTestType type;
        try {
            type = LoadTestType.fromValue(request.type());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
        LoadTest updated = loadTestRepository.updateType(testUuid, type);
        return LoadTestView.fromModel(
                updated,
                buildComplianceView(updated),
                kubernetesConfigurationService.findMaxRelease(testUuid),
                kubernetesConfigurationService.hasConfiguration(testUuid));
    }

    private RequirementComplianceView buildComplianceView(LoadTest loadTest) {
        return requirementEvaluationService.buildComplianceView(loadTest);
    }
}
