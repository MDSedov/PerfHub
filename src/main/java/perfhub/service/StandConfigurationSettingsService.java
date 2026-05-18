package perfhub.service;

import perfhub.dto.dropapp.DropAppServiceOptionDto;
import perfhub.dto.dropapp.DropAppServiceSelectionDto;
import perfhub.dto.settings.StandConfigurationSettingsDto;
import perfhub.dto.settings.StandConfigurationSettingsUpdateRequest;
import perfhub.model.ConfigurationEntry;
import perfhub.model.DropAppConfigurationComponent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Сервис настроек состава конфигурации стенда. */
@Service
public class StandConfigurationSettingsService {

    public static final String SERVICE = "stand-configuration";
    public static final String NAMESPACES_KEY = "namespaces";
    public static final String COMPONENTS_KEY = "components";
    public static final String SERVICES_KEY = "services";

    private static final TypeReference<List<DropAppServiceSelectionDto>> SERVICE_SELECTIONS_TYPE =
            new TypeReference<>() {};

    private final ConfigurationService configurationService;
    private final DropAppService dropAppService;
    private final ObjectMapper objectMapper;

    public StandConfigurationSettingsService(
            ConfigurationService configurationService,
            DropAppService dropAppService,
            ObjectMapper objectMapper) {
        this.configurationService = configurationService;
        this.dropAppService = dropAppService;
        this.objectMapper = objectMapper;
    }

    public StandConfigurationSettingsDto getSettings() {
        List<String> namespaces = resolveNamespaces();
        List<String> components = resolveComponentCodes();
        List<DropAppServiceSelectionDto> services = resolveServices(namespaces);
        List<DropAppServiceOptionDto> availableServices;
        try {
            availableServices = namespaces.isEmpty() ? List.of() : dropAppService.listServiceOptions(namespaces);
        } catch (Exception ignored) {
            availableServices = List.of();
        }
        return new StandConfigurationSettingsDto(namespaces, components, services, availableServices);
    }

    public StandConfigurationSettingsDto updateSettings(StandConfigurationSettingsUpdateRequest request) {
        List<String> namespaces = normalizeValues(request != null ? request.namespaces() : List.of());
        List<String> components = resolveComponentCodes(request != null ? request.components() : List.of());
        List<DropAppServiceSelectionDto> services = normalizeServices(
                request != null ? request.services() : List.of(),
                namespaces);

        configurationService.upsertAll(List.of(
                new ConfigurationEntry(null, SERVICE, NAMESPACES_KEY, String.join(", ", namespaces), null, null),
                new ConfigurationEntry(null, SERVICE, COMPONENTS_KEY, String.join(", ", components), null, null),
                new ConfigurationEntry(null, SERVICE, SERVICES_KEY, serializeServices(services), null, null)));
        return getSettings();
    }

    public List<String> resolveNamespaces() {
        List<String> standNamespaces = configurationService.findValue(SERVICE, NAMESPACES_KEY)
                .map(this::parseValues)
                .orElse(List.of());
        return standNamespaces.isEmpty() ? dropAppService.getConfiguredNamespaces() : standNamespaces;
    }

    public List<DropAppConfigurationComponent> resolveComponents() {
        return DropAppConfigurationComponent.resolve(resolveComponentCodes(), DropAppConfigurationComponent.defaultSelection());
    }

    public List<String> resolveComponentCodes() {
        return resolveComponentCodes(configurationService.findValue(SERVICE, COMPONENTS_KEY)
                .map(this::parseValues)
                .orElse(List.of()));
    }

    public List<DropAppServiceSelectionDto> resolveServices(List<String> namespaces) {
        List<DropAppServiceSelectionDto> savedServices = configurationService.findValue(SERVICE, SERVICES_KEY)
                .map(this::parseServices)
                .orElse(List.of());
        return normalizeServices(savedServices, namespaces);
    }

    private List<String> resolveComponentCodes(List<String> rawComponents) {
        return DropAppConfigurationComponent.resolve(rawComponents, DropAppConfigurationComponent.defaultSelection())
                .stream()
                .map(DropAppConfigurationComponent::code)
                .toList();
    }

    private List<String> parseValues(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return normalizeValues(Arrays.asList(value.split("[,\\s]+")));
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<DropAppServiceSelectionDto> parseServices(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, SERVICE_SELECTIONS_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String serializeServices(List<DropAppServiceSelectionDto> services) {
        if (services == null || services.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(services);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сохранить список сервисов конфигурации стенда", ex);
        }
    }

    private List<DropAppServiceSelectionDto> normalizeServices(
            List<DropAppServiceSelectionDto> services,
            List<String> namespaces) {
        if (services == null || services.isEmpty()) {
            return List.of();
        }
        List<String> fallbackNamespaces = normalizeValues(namespaces);
        Set<String> seen = new LinkedHashSet<>();
        List<DropAppServiceSelectionDto> result = new java.util.ArrayList<>();
        for (DropAppServiceSelectionDto service : services) {
            if (service == null || !StringUtils.hasText(service.serviceName())) {
                continue;
            }
            String namespace = normalizeText(service.namespace());
            String serviceName = normalizeText(service.serviceName());
            if (namespace != null) {
                addService(result, seen, namespace, serviceName);
                continue;
            }
            fallbackNamespaces.stream()
                    .forEach(fallbackNamespace -> addService(result, seen, fallbackNamespace, serviceName));
        }
        return result;
    }

    private void addService(
            List<DropAppServiceSelectionDto> result,
            Set<String> seen,
            String namespace,
            String serviceName) {
        String key = namespace.toLowerCase(java.util.Locale.ROOT) + "\n" + serviceName.toLowerCase(java.util.Locale.ROOT);
        if (seen.add(key)) {
            result.add(new DropAppServiceSelectionDto(namespace, serviceName));
        }
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

}
