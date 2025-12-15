package com.example.chronicler.controller;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.service.DropAppService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST-контроллер для работы с конфигурацией DropApp. */
@RestController
@RequestMapping(path = "/api/dropapp", produces = MediaType.APPLICATION_JSON_VALUE)
public class DropAppController {

    private final DropAppService dropAppService;

    public DropAppController(DropAppService dropAppService) {
        this.dropAppService = dropAppService;
    }

    /** Возвращает текущую конфигурацию DropApp для отображения на фронтенде. */
    @GetMapping("/configuration")
    public List<DropAppConfigurationItemDto> getCurrentConfiguration(
            @RequestParam(value = "namespace", required = false) List<String> namespaces) {
        return dropAppService.loadConfiguration(namespaces).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** Возвращает список доступных namespace из DropApp. */
    @GetMapping("/namespaces")
    public List<String> listNamespaces() {
        return dropAppService.listNamespaces();
    }

    /** Преобразует запись DropApp к DTO для фронтенда. */
    private DropAppConfigurationItemDto toDto(DropAppPodConfiguration configuration) {
        return new DropAppConfigurationItemDto(
                configuration.namespace(),
                configuration.serviceName(),
                configuration.podName(),
                configuration.containerName(),
                configuration.version(),
                configuration.release(),
                configuration.requestCpu(),
                configuration.limitCpu(),
                configuration.requestRam(),
                configuration.limitRam());
    }
}
