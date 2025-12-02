package com.example.chronicler.dto.dropapp;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Элемент конфигурации, полученный напрямую из DropApp. */
public record DropAppPodConfiguration(
        @JsonProperty("namespace") String namespace,
        @JsonProperty("service_name") @JsonAlias("serviceName") String serviceName,
        @JsonProperty("pod_name") @JsonAlias("podName") String podName,
        @JsonProperty("container_name") @JsonAlias("containerName") String containerName,
        @JsonProperty("version") String version,
        @JsonProperty("release") String release,
        @JsonProperty("request_cpu") @JsonAlias("requestCpu") String requestCpu,
        @JsonProperty("limit_cpu") @JsonAlias("limitCpu") String limitCpu,
        @JsonProperty("request_ram") @JsonAlias("requestRam") String requestRam,
        @JsonProperty("limit_ram") @JsonAlias("limitRam") String limitRam) {}
