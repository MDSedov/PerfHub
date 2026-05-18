package perfhub.dto.user;

public record ProfileUpdateRequest(
        String username,
        String firstName,
        String lastName,
        String password,
        String passwordConfirmation) {}
