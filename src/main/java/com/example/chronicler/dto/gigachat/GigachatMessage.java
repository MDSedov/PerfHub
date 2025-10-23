package com.example.chronicler.dto.gigachat;

import java.util.Objects;

public record GigachatMessage(String role, String content) {

    public GigachatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
