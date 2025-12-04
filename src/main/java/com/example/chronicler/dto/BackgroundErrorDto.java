package com.example.chronicler.dto;

import java.time.Instant;

public record BackgroundErrorDto(String message, Instant timestamp) {
}
