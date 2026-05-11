package com.example.chronicler.service;

import com.example.chronicler.model.ApplicationUser;
import com.example.chronicler.model.UserRole;
import com.example.chronicler.repository.ApplicationUserRepository;
import com.example.chronicler.security.PasswordHashService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ApplicationUserService {

    private static final String BOOTSTRAP_ADMIN_USERNAME = "admin";
    private static final String BOOTSTRAP_ADMIN_PASSWORD = "admin";

    private final ApplicationUserRepository userRepository;
    private final PasswordHashService passwordHashService;

    public ApplicationUserService(
            ApplicationUserRepository userRepository,
            PasswordHashService passwordHashService) {
        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
    }

    public List<ApplicationUser> listActiveUsers() {
        return userRepository.findAllActive();
    }

    public Optional<ApplicationUser> findActiveById(long id) {
        return userRepository.findActiveById(id);
    }

    public Optional<ApplicationUser> authenticate(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        if (!StringUtils.hasText(normalizedUsername) || password == null) {
            return Optional.empty();
        }
        return userRepository.findActiveByUsername(normalizedUsername)
                .filter(user -> passwordHashService.matches(password, user.passwordHash()));
    }

    @Transactional
    public ApplicationUser createUser(UserCommand command) {
        SanitizedUser sanitized = sanitize(command, true);
        validateUniqueUsername(sanitized.username(), null);
        long id = userRepository.create(
                sanitized.username(),
                sanitized.firstName(),
                sanitized.lastName(),
                sanitized.role(),
                passwordHashService.hash(sanitized.password()));
        return getActiveUser(id);
    }

    @Transactional
    public ApplicationUser updateUser(long id, UserCommand command) {
        getActiveUser(id);
        SanitizedUser sanitized = sanitize(command, false);
        validateUniqueUsername(sanitized.username(), id);
        String passwordHash = StringUtils.hasText(sanitized.password())
                ? passwordHashService.hash(sanitized.password())
                : null;
        userRepository.update(
                id,
                sanitized.username(),
                sanitized.firstName(),
                sanitized.lastName(),
                sanitized.role(),
                passwordHash);
        return getActiveUser(id);
    }

    @Transactional
    public ApplicationUser updateProfile(long id, ProfileCommand command) {
        ApplicationUser current = getActiveUser(id);
        SanitizedUser sanitized = sanitize(new UserCommand(
                command.username(),
                command.firstName(),
                command.lastName(),
                current.role().name(),
                command.password(),
                command.passwordConfirmation()), false);
        validateUniqueUsername(sanitized.username(), id);
        String passwordHash = StringUtils.hasText(sanitized.password())
                ? passwordHashService.hash(sanitized.password())
                : null;
        userRepository.update(
                id,
                sanitized.username(),
                sanitized.firstName(),
                sanitized.lastName(),
                current.role(),
                passwordHash);
        return getActiveUser(id);
    }

    @Transactional
    public void deleteUser(long id) {
        ApplicationUser user = getActiveUser(id);
        if (BOOTSTRAP_ADMIN_USERNAME.equalsIgnoreCase(user.username())) {
            throw new IllegalArgumentException("Пользователя admin нельзя удалить");
        }
        userRepository.softDelete(id);
    }

    @Transactional
    public ApplicationUser ensureBootstrapAdmin() {
        Optional<ApplicationUser> existingAdmin = userRepository.findActiveByUsername(BOOTSTRAP_ADMIN_USERNAME);
        if (existingAdmin.isPresent()) {
            return existingAdmin.get();
        }
        userRepository.create(
                BOOTSTRAP_ADMIN_USERNAME,
                "Администратор",
                "PerfHub",
                UserRole.ADMIN,
                passwordHashService.hash(BOOTSTRAP_ADMIN_PASSWORD));
        return userRepository.findActiveByUsername(BOOTSTRAP_ADMIN_USERNAME)
                .orElseThrow(() -> new IllegalStateException("Не удалось создать администратора admin"));
    }

    private ApplicationUser getActiveUser(long id) {
        return userRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
    }

    private SanitizedUser sanitize(UserCommand command, boolean passwordRequired) {
        if (command == null) {
            throw new IllegalArgumentException("Данные пользователя не переданы");
        }
        String username = normalizeUsername(command.username());
        String firstName = StringUtils.trimWhitespace(command.firstName());
        String lastName = StringUtils.trimWhitespace(command.lastName());
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Имя пользователя обязательно");
        }
        if (username.length() > 128) {
            throw new IllegalArgumentException("Имя пользователя должно быть не длиннее 128 символов");
        }
        if (!StringUtils.hasText(firstName)) {
            throw new IllegalArgumentException("Имя обязательно");
        }
        if (!StringUtils.hasText(lastName)) {
            throw new IllegalArgumentException("Фамилия обязательна");
        }
        UserRole role = UserRole.fromValue(command.role());
        String password = command.password() == null ? "" : command.password();
        String passwordConfirmation = command.passwordConfirmation() == null ? "" : command.passwordConfirmation();
        if (passwordRequired && !StringUtils.hasText(password)) {
            throw new IllegalArgumentException("Пароль обязателен");
        }
        if (StringUtils.hasText(password) || StringUtils.hasText(passwordConfirmation)) {
            if (!password.equals(passwordConfirmation)) {
                throw new IllegalArgumentException("Пароль и подтверждение пароля не совпадают");
            }
        }
        return new SanitizedUser(username, firstName, lastName, role, password);
    }

    private void validateUniqueUsername(String username, Long excludedId) {
        if (userRepository.existsActiveUsername(username, excludedId)) {
            throw new IllegalArgumentException("Пользователь с таким именем уже существует");
        }
    }

    private String normalizeUsername(String username) {
        return StringUtils.trimWhitespace(username);
    }

    public record UserCommand(
            String username,
            String firstName,
            String lastName,
            String role,
            String password,
            String passwordConfirmation) {}

    public record ProfileCommand(
            String username,
            String firstName,
            String lastName,
            String password,
            String passwordConfirmation) {}

    private record SanitizedUser(
            String username,
            String firstName,
            String lastName,
            UserRole role,
            String password) {}
}
