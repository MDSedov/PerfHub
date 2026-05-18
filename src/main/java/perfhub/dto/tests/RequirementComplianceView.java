package perfhub.dto.tests;

/** Представление сведений о соответствии теста нефункциональным требованиям. */
public record RequirementComplianceView(
        boolean evaluated,
        Boolean compliant,
        String evaluatedAt) {}
