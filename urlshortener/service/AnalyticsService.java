package urlshortener.service;

import urlshortener.model.ClickEvent;
import urlshortener.model.DailyStat;
import urlshortener.store.DataStore;
import urlshortener.store.ZoneHelper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис асинхронной аналитики: забирает click events и пишет дневные агрегаты.
 */
public final class AnalyticsService {

    private final ClickEventBus clickEventBus;
    private final DataStore dataStore;

    public AnalyticsService(ClickEventBus clickEventBus, DataStore dataStore) {
        this.clickEventBus = clickEventBus;
        this.dataStore = dataStore;
    }

    /**
     * Показывает текущий backlog событий кликов.
     */
    public int queueSize() {
        return clickEventBus.size();
    }

    public void flushClickEvents() {
        List<ClickEventBus.Envelope> batch = clickEventBus.readBatch(10_000, java.time.Duration.ofSeconds(1));
        if (batch.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Map<UUID, Map<LocalDate, Long>> aggregated = new HashMap<>();

        for (ClickEventBus.Envelope envelope : batch) {
            ClickEvent event = envelope.event();
            LocalDate statDate = event.clickedAt().atZone(ZoneHelper.moscow()).toLocalDate();
            aggregated.computeIfAbsent(event.shortLinkId(), ignored -> new HashMap<>())
                    .merge(statDate, 1L, Long::sum);
        }

        dataStore.applyAggregatedClicks(aggregated, now);
        clickEventBus.acknowledge(batch);
    }

    /**
     * Возвращает статистику ссылки за период хранения НФТ.
     */
    public List<DailyStat> getLast90Days(UUID shortLinkId) {
        return dataStore.findStatsByLink(shortLinkId, 90);
    }
}
