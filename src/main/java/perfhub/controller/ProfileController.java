package perfhub.controller;

import perfhub.dto.user.ProfileUpdateRequest;
import perfhub.dto.user.UserDto;
import perfhub.model.ApplicationUser;
import perfhub.security.SessionAttributes;
import perfhub.service.ApplicationUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/profile", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProfileController {

    private final ApplicationUserService userService;

    public ProfileController(ApplicationUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserDto currentUser(HttpServletRequest request) {
        return toDto(userService.findActiveById(currentUserId(request))
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден")));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserDto updateProfile(
            @RequestBody ProfileUpdateRequest request,
            HttpServletRequest httpRequest) {
        ApplicationUser user = userService.updateProfile(
                currentUserId(httpRequest),
                new ApplicationUserService.ProfileCommand(
                        request.username(),
                        request.firstName(),
                        request.lastName(),
                        request.password(),
                        request.passwordConfirmation()));
        return toDto(user);
    }

    private long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(SessionAttributes.CURRENT_USER_ID);
        if (userId instanceof Number value) {
            return value.longValue();
        }
        throw new IllegalArgumentException("Пользователь не найден");
    }

    private UserDto toDto(ApplicationUser user) {
        return new UserDto(
                user.id(),
                user.username(),
                user.firstName(),
                user.lastName(),
                user.fullName(),
                user.role().name(),
                user.role().displayName());
    }
}
