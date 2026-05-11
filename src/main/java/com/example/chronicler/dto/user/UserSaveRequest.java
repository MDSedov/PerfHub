package com.example.chronicler.dto.user;

public record UserSaveRequest(
        String username,
        String firstName,
        String lastName,
        String role,
        String password,
        String passwordConfirmation) {}
