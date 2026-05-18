package perfhub.dto.user;

public record UserDto(
        long id,
        String username,
        String firstName,
        String lastName,
        String fullName,
        String role,
        String roleLabel) {}
