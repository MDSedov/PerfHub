package perfhub.dto.user;

public record UserSaveRequest(
        String username,
        String firstName,
        String lastName,
        String role,
        String password,
        String passwordConfirmation) {}
