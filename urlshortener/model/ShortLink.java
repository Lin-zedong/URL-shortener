package urlshortener.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Основная доменная сущность короткой ссылки с owner, route_key, URL, статусом и счётчиком кликов.
 */
public record ShortLink(
        UUID id,
        UUID ownerUserId,
        String routeKey,
        String originalUrl,
        LinkStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long totalClicks,
        boolean customAlias
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Проверка просрочки по expiresAt.
     */
    public boolean isExpiredByTime(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * Вычисление «эффективного статуса» относительно текущего времени.
     */
    public LinkStatus effectiveStatus(Instant now) {
        if (status == LinkStatus.DELETED) {
            return LinkStatus.DELETED;
        }
        if (status == LinkStatus.DISABLED) {
            return LinkStatus.DISABLED;
        }
        if (status == LinkStatus.EXPIRED || isExpiredByTime(now)) {
            return LinkStatus.EXPIRED;
        }
        return LinkStatus.ACTIVE;
    }

    /**
     * Копия объекта с новым статусом.
     */
    public ShortLink withStatus(LinkStatus newStatus, Instant now) {
        return new ShortLink(id, ownerUserId, routeKey, originalUrl, newStatus, createdAt, now, expiresAt, totalClicks, customAlias);
    }

    /**
     * Копия объекта с обновлённым totalClicks.
     */
    public ShortLink withTotalClicks(long newTotalClicks, Instant now) {
        return new ShortLink(id, ownerUserId, routeKey, originalUrl, status, createdAt, now, expiresAt, newTotalClicks, customAlias);
    }

    /**
     * Перевести активную ссылку в статус EXPIRED.
     */
    public ShortLink markExpired(Instant now) {
        return new ShortLink(id, ownerUserId, routeKey, originalUrl, LinkStatus.EXPIRED, createdAt, now, expiresAt, totalClicks, customAlias);
    }
}
