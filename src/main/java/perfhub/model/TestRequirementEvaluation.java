package perfhub.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record TestRequirementEvaluation(
        long id,
        UUID testUuid,
        String dashboardUid,
        int panelId,
        RequirementEvaluationStatus status,
        Double measuredValue,
        String unit,
        JsonNode details,
        Instant evaluatedAt) {}
