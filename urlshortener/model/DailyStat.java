package urlshortener.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Агрегированная дневная статистика переходов по одной короткой ссылке.
 */
public record DailyStat(
        UUID shortLinkId,
        LocalDate statDate,
        long clickCount,
        Instant updatedAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Возвращает новый агрегат с добавленным числом кликов.
     */
    public DailyStat increment(long delta, Instant now) {
        return new DailyStat(shortLinkId, statDate, clickCount + delta, now);
    }

    /**
     * Формирует стабильный ключ составного идентификатора статистики.
     */
    public String key() {
        return shortLinkId + "|" + statDate;
    }
}
