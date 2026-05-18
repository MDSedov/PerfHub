package perfhub.dto.configuration;

public record ConnectionTestResponse(String service, boolean success, String message) {
}
