package urlshortener.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Владелец ссылок, который входит в личный кабинет и управляет своими записями.
 */
public record User(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        String passwordSalt,
        Instant createdAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
