package com.example.chronicler.dto.user;

public record ProfileUpdateRequest(
        String username,
        String firstName,
        String lastName,
        String password,
        String passwordConfirmation) {}
