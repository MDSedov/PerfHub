package com.example.chronicler.controller;

import com.example.chronicler.dto.tests.LoadTestView;
import com.example.chronicler.dto.tests.RequirementComplianceView;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST-контроллер для работы со списком нагрузочных тестов. */
@RestController
@RequestMapping(path = "/api/tests", produces = MediaType.APPLICATION_JSON_VALUE)
public class LoadTestController {

    private final LoadTestRepository loadTestRepository;
    private final TestRequirementEvaluationRepository requirementEvaluationRepository;

    public LoadTestController(
            LoadTestRepository loadTestRepository,
            TestRequirementEvaluationRepository requirementEvaluationRepository) {
        this.loadTestRepository = loadTestRepository;
        this.requirementEvaluationRepository = requirementEvaluationRepository;
    }

    @GetMapping
    public List<LoadTestView> listTests() {
        return loadTestRepository.findAllOrderByStartDesc().stream()
                .map(loadTest -> LoadTestView.fromModel(loadTest, buildComplianceView(loadTest.id())))
                .collect(Collectors.toList());
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
