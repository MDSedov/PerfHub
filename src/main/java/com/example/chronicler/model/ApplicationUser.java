package com.example.chronicler.model;

import java.time.LocalDateTime;

public record ApplicationUser(
        long id,
        String username,
        String firstName,
        String lastName,
        UserRole role,
        String passwordHash,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public String fullName() {
        String normalizedFirstName = firstName == null ? "" : firstName.trim();
        String normalizedLastName = lastName == null ? "" : lastName.trim();
        String value = (normalizedFirstName + " " + normalizedLastName).trim();
        return value.isEmpty() ? username : value;
    }

    public boolean admin() {
        return role == UserRole.ADMIN;
    }
}
