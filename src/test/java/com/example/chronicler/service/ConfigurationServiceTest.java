package com.example.chronicler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.model.ConfigurationEntry;
import com.example.chronicler.repository.ConfigurationRepository;
import com.example.chronicler.security.CurrentUserProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigurationServiceTest {

    @Mock
    private ConfigurationRepository repository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private ConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new ConfigurationService(repository, currentUserProvider);
    }

    @Test
    void fetchAllReadsOnlyCurrentUsersConnectionValues() {
        when(currentUserProvider.currentUserId()).thenReturn(Optional.of(42L));
        when(repository.findAllOrdered(42L)).thenReturn(List.of(
                new ConfigurationEntry(null, "dropapp", "base-url", "https://current-user", null, 10)));

        List<ConfigurationEntry> entries = service.fetchAll();

        assertEquals("https://current-user", entries.get(0).value());
        verify(repository).copyGlobalConnectionValuesToUser(42L);
        verify(repository).findAllOrdered(42L);
    }

    @Test
    void anonymousAccessFallsBackToGlobalConfiguration() {
        when(currentUserProvider.currentUserId()).thenReturn(Optional.empty());
        when(repository.findValue("dropapp", "base-url", null)).thenReturn(Optional.of("https://global"));

        assertEquals("https://global", service.findValue("dropapp", "base-url").orElseThrow());
        verify(repository, never()).copyGlobalConnectionValuesToUser(42L);
        verify(repository).findValue("dropapp", "base-url", null);
    }

    @Test
    void getRequiredValueRejectsBlankConnectionValue() {
        when(currentUserProvider.currentUserId()).thenReturn(Optional.of(42L));
        when(repository.findValue("dropapp", "token", 42L)).thenReturn(Optional.of(" "));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.getRequiredValue("dropapp", "token"));

        assertEquals("Не задан обязательный параметр подключения dropapp.token", error.getMessage());
        verify(repository).copyGlobalConnectionValuesToUser(42L);
    }
}
