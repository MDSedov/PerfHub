package com.example.chronicler.dto.user;

public record UserDto(
        long id,
        String username,
        String firstName,
        String lastName,
        String fullName,
        String role,
        String roleLabel) {}
