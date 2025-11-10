package com.example.chronicler.dto.dropapp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Обертка ответа DropApp с конфигурацией контейнеров. */
public record DropAppConfigurationResponse(@JsonProperty("items") List<DropAppPodConfiguration> items) {}
