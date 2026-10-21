package perfhub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import perfhub.model.ConfigurationEntry;
import perfhub.repository.ConfigurationRepository;
import perfhub.security.CurrentUserProvider;
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

    @Test
    void sourceChangeRotatesOnlyCurrentOwnerWhileDimensionsKeepCatalogRevision() {
        var catalog = org.mockito.Mockito.mock(perfhub.repository.GrafanaRenderCatalogRepository.class);
        var settings = new ConfigurationService(repository,currentUserProvider,catalog);
        when(currentUserProvider.currentUserId()).thenReturn(Optional.of(42L));
        settings.upsertAll(List.of(new ConfigurationEntry(null,"grafana","image-height","1000",null,1)));
        verify(catalog,never()).rotate(42L);
        settings.upsertAll(List.of(new ConfigurationEntry(null,"grafana","base-url","https://grafana.example",null,1)));
        verify(catalog).rotate(42L);
        verify(catalog,never()).rotate(0L);
    }
}
