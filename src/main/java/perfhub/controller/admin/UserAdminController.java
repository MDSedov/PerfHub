package perfhub.controller.admin;

import perfhub.dto.user.UserDto;
import perfhub.dto.user.UserSaveRequest;
import perfhub.model.ApplicationUser;
import perfhub.model.UserRole;
import perfhub.service.ApplicationUserService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserAdminController {

    private final ApplicationUserService userService;

    public UserAdminController(ApplicationUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserDto> listUsers() {
        return userService.listActiveUsers().stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/roles")
    public List<Map<String, String>> listRoles() {
        return Arrays.stream(UserRole.values())
                .map(role -> Map.of(
                        "value", role.name(),
                        "label", role.displayName()))
                .toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserDto createUser(@RequestBody UserSaveRequest request) {
        return toDto(userService.createUser(toCommand(request)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserDto updateUser(
            @PathVariable("id") long id,
            @RequestBody UserSaveRequest request) {
        return toDto(userService.updateUser(id, toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable("id") long id) {
        userService.deleteUser(id);
    }

    private ApplicationUserService.UserCommand toCommand(UserSaveRequest request) {
        return new ApplicationUserService.UserCommand(
                request.username(),
                request.firstName(),
                request.lastName(),
                request.role(),
                request.password(),
                request.passwordConfirmation());
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
