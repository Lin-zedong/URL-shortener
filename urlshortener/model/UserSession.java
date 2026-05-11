package urlshortener.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Пользовательская сессия с хешем токена, сроком действия и временем последней активности.
 */
public record UserSession(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessAt,
        String userAgent
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Проверяет, истекла ли сессия на текущий момент.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UserSession touch(Instant now) {
        return new UserSession(id, userId, tokenHash, createdAt, expiresAt, now, userAgent);
    }
}
