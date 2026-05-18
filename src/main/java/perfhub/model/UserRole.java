package perfhub.model;

public enum UserRole {
    USER("Пользователь"),
    ADMIN("Администратор");

    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static UserRole fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Роль пользователя не указана");
        }
        return switch (value.trim().toUpperCase()) {
            case "USER", "ПОЛЬЗОВАТЕЛЬ" -> USER;
            case "ADMIN", "АДМИНИСТРАТОР" -> ADMIN;
            default -> throw new IllegalArgumentException("Неизвестная роль пользователя: " + value);
        };
    }
}
