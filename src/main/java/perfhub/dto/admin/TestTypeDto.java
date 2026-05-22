package perfhub.dto.admin;

public record TestTypeDto(
        int id,
        String name,
        String code,
        boolean standard,
        String typeLabel) {}
