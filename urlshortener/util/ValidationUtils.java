package urlshortener.util;

import urlshortener.config.AppConfig;

import java.net.URI;
import java.net.URISyntaxException;
import urlshortener.store.ZoneHelper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Централизованная валидация URL, username, alias, пароля и времени истечения.
 */
public final class ValidationUtils {

    private ValidationUtils() {
    }

    public static String normalizeHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Исходный URL не может быть пустым");
        }
        String candidate = rawUrl.trim();
        try {
            URI uri = new URI(candidate).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Разрешены только http/https URL");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("URL должен содержать host");
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Некорректный формат URL");
        }
    }

    public static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username не может быть пустым");
        }
        String trimmed = username.trim();
        if (!AppConfig.USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Username должен состоять из 3-32 символов [A-Za-z0-9_-]");
        }
        return trimmed;
    }

    /**
     * Проверяет формат alias по требованиям и reserved words.
     */
    public static String normalizeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String trimmed = alias.trim();
        if (!AppConfig.ALIAS_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Alias должен соответствовать [A-Za-z0-9_-] и иметь длину 3-32");
        }
        if (AppConfig.RESERVED_ROUTE_KEYS.contains(trimmed)) {
            throw new IllegalArgumentException("Этот alias зарезервирован системой");
        }
        return trimmed;
    }

    public static void validatePassword(String password, String confirmPassword) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Пароль должен быть не короче 8 символов");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Пароли не совпадают");
        }
    }

    /**
     * Разбирает срок действия как Moscow time и хранит Instant.
     */
    public static Instant parseOptionalExpiresAtMoscow(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String trimmed = rawValue.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(trimmed);
                return localDateTime.atZone(ZoneHelper.moscow()).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("ExpiresAt должен быть указан по московскому времени, например 2026-03-18T15:30:00 или 2026-03-18T15:30:00+03:00");
            }
        }
    }
}
