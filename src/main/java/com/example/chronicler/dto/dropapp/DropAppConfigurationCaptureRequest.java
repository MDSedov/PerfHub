package com.example.chronicler.dto.dropapp;

import java.util.List;

public record DropAppConfigurationCaptureRequest(
        List<String> namespaces,
        List<String> components,
        String comment) {}
