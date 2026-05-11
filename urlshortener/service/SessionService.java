package urlshortener.service;

import com.sun.net.httpserver.HttpExchange;
import urlshortener.config.AppConfig;
import urlshortener.model.User;
import urlshortener.model.UserSession;
import urlshortener.store.DataStore;
import urlshortener.util.HttpUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис сессий: создаёт, валидирует и завершает пользовательские сессии.
 */
public final class SessionService {

    private final DataStore dataStore;
    private final UserService userService;

    public SessionService(DataStore dataStore, UserService userService) {
        this.dataStore = dataStore;
        this.userService = userService;
    }

    /**
     * Создаёт raw token, сохраняет только hash и возвращает значение cookie.
     */
    public String createSession(User user, String userAgent) {
        String rawToken = UUID.randomUUID() + "-" + UUID.randomUUID();
        String tokenHash = sha256(rawToken);
        Instant now = Instant.now();
        UserSession session = new UserSession(
                UUID.randomUUID(),
                user.id(),
                tokenHash,
                now,
                now.plus(AppConfig.SESSION_TTL),
                now,
                userAgent
        );
        dataStore.saveSession(session);
        return rawToken;
    }

    /**
     * Проверяет cookie и возвращает текущего пользователя.
     */
    public Optional<User> resolveAuthenticatedUser(HttpExchange exchange) {
        String rawToken = HttpUtils.extractSessionCookieValue(exchange);
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = sha256(rawToken);
        Optional<UserSession> optionalSession = dataStore.findSessionByTokenHash(tokenHash);
        if (optionalSession.isEmpty()) {
            return Optional.empty();
        }
        UserSession session = optionalSession.get();
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            dataStore.deleteSessionByTokenHash(tokenHash);
            return Optional.empty();
        }
        if (session.lastAccessAt() == null || session.lastAccessAt().plusSeconds(60).isBefore(now)) {
            dataStore.saveSession(session.touch(now));
        }
        return userService.findById(session.userId());
    }

    /**
     * Удаляет серверную сессию и очищает cookie браузера.
     */
    public void logout(HttpExchange exchange) {
        String rawToken = HttpUtils.extractSessionCookieValue(exchange);
        if (rawToken != null && !rawToken.isBlank()) {
            dataStore.deleteSessionByTokenHash(sha256(rawToken));
        }
    }

    public int cleanupExpiredSessions() {
        return dataStore.deleteExpiredSessions(Instant.now());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
