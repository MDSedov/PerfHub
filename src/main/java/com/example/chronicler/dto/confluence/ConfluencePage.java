package com.example.chronicler.dto.confluence;

public record ConfluencePage(
        String id, String type, String title, int version, String body, String spaceKey) {
}
