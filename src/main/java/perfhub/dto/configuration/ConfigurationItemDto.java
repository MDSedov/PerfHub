package perfhub.dto.configuration;

public record ConfigurationItemDto(
        Long id, String service, String key, String value, String description, Integer sortOrder) {
}
