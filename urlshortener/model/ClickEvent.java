package urlshortener.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие перехода по короткой ссылке, которое передаётся в асинхронную аналитику.
 */
public record ClickEvent(
        UUID shortLinkId,
        String routeKey,
        Instant clickedAt
) {
}
