package com.example.chronicler.controller;

import com.example.chronicler.dto.RequirementEvaluationRequest;
import com.example.chronicler.dto.RequirementEvaluationResponse;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.service.RequirementEvaluationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/requirements", produces = MediaType.APPLICATION_JSON_VALUE)
public class RequirementController {

    private final RequirementEvaluationService requirementEvaluationService;

    public RequirementController(RequirementEvaluationService requirementEvaluationService) {
        this.requirementEvaluationService = requirementEvaluationService;
    }

    @PostMapping(path = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequirementEvaluationResponse> evaluate(
            @Valid @RequestBody RequirementEvaluationRequest request) {
        List<RequirementEvaluationView> evaluations = requirementEvaluationService.evaluate(request.testUuid());
        return ResponseEntity.ok(buildResponse(request.testUuid(), evaluations));
    }

    @PostMapping(path = "/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequirementEvaluationResponse> status(
            @Valid @RequestBody RequirementEvaluationRequest request) {
        List<RequirementEvaluationView> evaluations = requirementEvaluationService.getCachedOrEvaluate(request.testUuid());
        return ResponseEntity.ok(buildResponse(request.testUuid(), evaluations));
    }

    private RequirementEvaluationResponse buildResponse(
            java.util.UUID testUuid, List<RequirementEvaluationView> evaluations) {
        boolean compliant = evaluations.stream()
                .allMatch(view -> view.status() == RequirementEvaluationStatus.PASS
                        || view.status() == RequirementEvaluationStatus.NOT_APPLICABLE);
        return new RequirementEvaluationResponse(testUuid, compliant, evaluations);
    }
}
