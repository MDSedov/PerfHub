package com.example.chronicler.model;

import java.time.Instant;
import java.util.UUID;

public record LoadTest(UUID id, Instant start, Instant end, LoadTestType type, String comment) {}
