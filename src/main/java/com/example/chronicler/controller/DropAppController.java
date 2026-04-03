package com.example.chronicler.controller;

import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSaveRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSummaryDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationViewDto;
import com.example.chronicler.dto.dropapp.DropAppNamespaceOptionsDto;
import com.example.chronicler.service.DropAppConfigurationSnapshotService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST-контроллер для работы с конфигурацией DropApp. */
@RestController
@RequestMapping(path = "/api/dropapp", produces = MediaType.APPLICATION_JSON_VALUE)
public class DropAppController {

    private final DropAppConfigurationSnapshotService snapshotService;

    public DropAppController(DropAppConfigurationSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /** Возвращает текущую конфигурацию DropApp для отображения на фронтенде. */
    @GetMapping("/configuration")
    public DropAppConfigurationViewDto getCurrentConfiguration(
            @RequestParam(value = "namespace", required = false) List<String> namespaces) {
        return snapshotService.loadCurrentConfiguration(namespaces);
    }

    /** Возвращает список сохранённых конфигураций DropApp. */
    @GetMapping("/configuration/saved")
    public List<DropAppConfigurationSnapshotSummaryDto> listSavedConfigurations() {
        return snapshotService.listSnapshots();
    }

    /** Возвращает детальную информацию о сохранённой конфигурации DropApp. */
    @GetMapping("/configuration/saved/{snapshotId}")
    public DropAppConfigurationSnapshotDetailsDto getSavedConfiguration(@PathVariable("snapshotId") Long snapshotId) {
        return snapshotService.findSnapshotDetails(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Сохраненная конфигурация DropApp не найдена"));
    }

    /** Сохраняет снапшот текущей конфигурации DropApp. */
    @PostMapping(path = "/configuration/saved", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DropAppConfigurationSnapshotDetailsDto saveConfiguration(
            @RequestBody DropAppConfigurationSnapshotSaveRequest request) {
        return snapshotService.saveSnapshot(request);
    }

    /** Возвращает список доступных namespace из DropApp. */
    @GetMapping("/namespaces")
    public DropAppNamespaceOptionsDto listNamespaces() {
        return snapshotService.getNamespaceOptions();
    }
}
