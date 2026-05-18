package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.model.ApplicationUser;
import com.example.chronicler.model.UserRole;
import com.example.chronicler.repository.ApplicationUserRepository;
import com.example.chronicler.security.PasswordHashService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationUserServiceTest {

    @Mock
    private ApplicationUserRepository userRepository;

    @Mock
    private PasswordHashService passwordHashService;

    private ApplicationUserService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationUserService(userRepository, passwordHashService);
    }

    @Test
    void createUserAcceptsBlankLastName() {
        ApplicationUser user = user(7L, "ivan", "Иван", "", UserRole.USER);
        when(passwordHashService.hash("secret")).thenReturn("hash");
        when(userRepository.create("ivan", "Иван", "", UserRole.USER, "hash")).thenReturn(7L);
        when(userRepository.findActiveById(7L)).thenReturn(Optional.of(user));

        ApplicationUser created = service.createUser(new ApplicationUserService.UserCommand(
                "ivan",
                "Иван",
                "   ",
                "USER",
                "secret",
                "secret"));

        assertThat(created.lastName()).isEmpty();
        verify(userRepository).create("ivan", "Иван", "", UserRole.USER, "hash");
    }

    @Test
    void ensureBootstrapAdminCreatesAdminWithoutDefaultLastName() {
        ApplicationUser admin = user(1L, "admin", "Администратор", "", UserRole.ADMIN);
        when(userRepository.findActiveByUsername("admin"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(admin));
        when(passwordHashService.hash("admin")).thenReturn("hash");

        ApplicationUser ensured = service.ensureBootstrapAdmin();

        assertThat(ensured.lastName()).isEmpty();
        verify(userRepository).create("admin", "Администратор", "", UserRole.ADMIN, "hash");
    }

    @Test
    void ensureBootstrapAdminClearsLegacyDefaultLastName() {
        ApplicationUser legacyAdmin = user(1L, "admin", "Администратор", "PerfHub", UserRole.ADMIN);
        ApplicationUser updatedAdmin = user(1L, "admin", "Администратор", "", UserRole.ADMIN);
        when(userRepository.findActiveByUsername("admin")).thenReturn(Optional.of(legacyAdmin));
        when(userRepository.findActiveById(1L)).thenReturn(Optional.of(updatedAdmin));

        ApplicationUser ensured = service.ensureBootstrapAdmin();

        assertThat(ensured.lastName()).isEmpty();
        verify(userRepository).update(1L, "admin", "Администратор", "", UserRole.ADMIN, null);
    }

    private static ApplicationUser user(
            long id,
            String username,
            String firstName,
            String lastName,
            UserRole role) {
        return new ApplicationUser(
                id,
                username,
                firstName,
                lastName,
                role,
                "hash",
                null,
                LocalDateTime.parse("2026-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));
    }
}
