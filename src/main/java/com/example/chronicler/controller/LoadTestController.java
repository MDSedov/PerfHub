package com.example.chronicler.controller;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.tests.LoadTestView;
import com.example.chronicler.dto.tests.RequirementComplianceView;
import com.example.chronicler.dto.tests.SaveTestCommentRequest;
import com.example.chronicler.dto.tests.SaveTestTypeRequest;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.example.chronicler.service.KubernetesConfigurationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final TestRequirementEvaluationRepository requirementEvaluationRepository;
    private final KubernetesConfigurationService kubernetesConfigurationService;

    public LoadTestController(
            LoadTestRepository loadTestRepository,
            TestRequirementEvaluationRepository requirementEvaluationRepository,
            KubernetesConfigurationService kubernetesConfigurationService) {
        this.loadTestRepository = loadTestRepository;
        this.requirementEvaluationRepository = requirementEvaluationRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
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
                        buildComplianceView(loadTest.id()),
                        releases.get(loadTest.id()),
                        configuredTests.contains(loadTest.id())))
                .collect(Collectors.toList());
    }

    @GetMapping("/{testUuid}/configuration")
    public List<DropAppConfigurationItemDto> getTestConfiguration(@PathVariable("testUuid") UUID testUuid) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        return kubernetesConfigurationService.getConfigurationItems(testUuid);
    }

    /** Запрашивает и сохраняет текущую конфигурацию DropApp для теста, если она отсутствует. */
    @PostMapping("/{testUuid}/configuration/capture")
    public List<DropAppConfigurationItemDto> captureConfiguration(@PathVariable("testUuid") UUID testUuid) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        return kubernetesConfigurationService.captureAndGetConfiguration(testUuid);
    }

    /** Сохраняет комментарий для теста и возвращает обновленную информацию. */
    @PostMapping("/{testUuid}/comment")
    public LoadTestView saveComment(
            @PathVariable("testUuid") UUID testUuid, @RequestBody SaveTestCommentRequest request) {
        Objects.requireNonNull(testUuid, "testUuid must not be null");
        LoadTest updated = loadTestRepository.updateComment(testUuid, request != null ? request.comment() : null);
        return LoadTestView.fromModel(
                updated,
                buildComplianceView(updated.id()),
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
                buildComplianceView(updated.id()),
                kubernetesConfigurationService.findMaxRelease(testUuid),
                kubernetesConfigurationService.hasConfiguration(testUuid));
    }

    private RequirementComplianceView buildComplianceView(UUID testUuid) {
        List<TestRequirementEvaluation> evaluations = requirementEvaluationRepository.findByTest(testUuid);
        if (evaluations.isEmpty()) {
            return new RequirementComplianceView(false, null, null);
        }
        boolean compliant = evaluations.stream()
                .allMatch(eval -> eval.status() == RequirementEvaluationStatus.PASS
                        || eval.status() == RequirementEvaluationStatus.NOT_APPLICABLE);
        Instant evaluatedAt = evaluations.stream()
                .map(TestRequirementEvaluation::evaluatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        String formatted = evaluatedAt != null ? LoadTestView.formatInstant(evaluatedAt) : null;
        return new RequirementComplianceView(true, compliant, formatted);
    }
}
